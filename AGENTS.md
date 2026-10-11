# Centauro Billing

## Propósito

Suscripciones de los tenants de Centauro: planes, precios, trial, mora y nivel
de acceso. El cobro recurrente con Mercado Pago llega en PR1b; hoy solo
existe el port `PaymentGateway`.

Responsabilidades:
- planes y precios versionados (ARS, mensual)
- suscripción por tenant y su máquina de estados
- trial de 14 días sin tarjeta
- pagos manuales registrados desde el backoffice
- nivel de acceso (FULL / READ_ONLY / BLOCKED) para los demás servicios
- historial de eventos de la suscripción
- (PR1b) checkout y cobro recurrente con Mercado Pago (preapproval)

## Stack

- Java 21
- Spring Boot 3.5.13
- Spring Data JPA / PostgreSQL (base propia)
- Flyway (declarado, deshabilitado al arrancar)
- Spring Security
- jjwt 0.12.6 (solo valida, no emite)
- springdoc-openapi 2.8.0
- Lombok (solo en entidades JPA)
- Maven (wrapper incluido)

Artefacto: `com.centauro:billing`. Paquete base: `com.centauro.billing`.
Puerto: 8087 (`PORT` en Heroku). Jar: `target/billing.jar` (`Procfile`).

Sin SDK de Mercado Pago: el cliente de PR1b será `RestClient`. No agregar el SDK.
Sin MapStruct: mapeo manual.

## Arquitectura

Hexagonal, igual que centauro-fiscal. Mantenerla.

```
com.centauro.billing
├── domain/ model, enums, vo, policy, port, exception
├── application/ usecase, service, job, dto
├── infrastructure/ jpa, adapter, lock (PR1b: mercadopago)
├── web/ controller, security, filter, exception, openapi
└── config/
```

`domain` no importa Spring, `jakarta.persistence` ni Hibernate
(`DomainPurityTest` lo verifica).
Toda dependencia externa entra como port en `domain/port`.
Las entidades JPA viven en `infrastructure/jpa/entity` y no se mezclan con
`domain/model`.

Las transiciones de estado son métodos de `Subscription` y siempre registran
un `SubscriptionEvent`. No cambiar `status` con un setter desde un use case.

Usar el `Clock` inyectado. Nunca `now()` sin clock.

## Límites

Este servicio solo cobra la suscripción a Centauro.

NO implementar ventas, stock, caja ni compras.
NO hablar con AFIP. La factura de la cuota no es parte de este servicio.
NO emitir JWT ni administrar usuarios o permisos.
NO decidir permisos RBAC: el plan y el RBAC son chequeos separados.

Los consumidores (pos-manager, purchase-manager) deciden qué bloquear con
`accessLevel`. La regla de qué endpoints siguen permitidos en READ_ONLY vive
en cada consumidor, no acá.

## Seguridad

| Rutas | Auth |
|---|---|
| `/api/v1/billing/**` | JWT del usuario (HS256, `JWT_SECRET`) |
| `/api/v1/platform/**` | JWT con `PLATFORM_ADMIN` en `permissions` (estricto) |
| `/internal/v1/**` | header `X-Internal-Api-Key` |
| `/webhooks/**` | `denyAll` hasta PR1b (ahí pasa a público con firma `x-signature`) |

Cada superficie es un `SecurityFilterChain` propio en `config/SecurityConfig`.
Los filtros se instancian ahí; no registrarlos como `@Component` (Spring Boot
los agregaría a todas las rutas). Dentro de `/api/**`, cualquier ruta que no
sea `/api/v1/billing/**` ni `/api/v1/platform/**` se deniega; fuera de esas
cadenas solo son públicos `/actuator/health` y, si `SPRINGDOC_ENABLED`, la
documentación.

`/api/v1/platform/**` exige `hasAuthority("PLATFORM_ADMIN")` en la URL y
además `@PreAuthorize("@billingAuth.isPlatformAdmin()")` en los controllers.
`JwtTokenFilter` mapea el rol como `ROLE_<rol>` y los permisos tal cual, así
que un rol llamado `PLATFORM_ADMIN` no concede nada: solo el permiso.

`UserDetailsServiceAutoConfiguration` está excluida: no hay usuarios locales.

`SecurityWiringTest` (`@WebMvcTest`, sin base) cubre el cableado de las
cadenas. Si tocás `SecurityConfig`, ampliarlo.

Gestión del tenant: rol `ADMIN` o permiso `BILLING_MANAGE`, chequeo estricto
(`@billingAuth.canManage()`), sin modo sombra ni pase para tokens legacy.
El rol se lee de `role` y, si falta, de `roleId.authority`.

`PLATFORM_ADMIN` es estricto: el rol ADMIN del tenant no lo concede.

Comparar API keys y firmas con `MessageDigest.isEqual`.

Nunca loguear la API key interna, el secreto JWT, tokens de Mercado Pago ni
emails completos. Para identificar a quien opera, loguear `Actor.logId()` (el
claim `id`), nunca el `sub` (username).

## Multitenancy

En `/api/v1/billing/**` el `tenantId` sale siempre del JWT
(`TenantContextHolder.requireTenantId()`). No aceptarlo por body, query ni header.

En `/internal/**` y `/api/v1/platform/**` viaja en el path porque el llamador
es de confianza (API key o platform admin).

Una suscripción por tenant (`uq_subscriptions_tenant`). El trial se crea con
`INSERT ... ON CONFLICT DO NOTHING`, así que es idempotente.

## Pasarela de pago

`domain/port/PaymentGateway` define la interfaz. En PR1a no hay
implementación productiva: `GatewayOperations` resuelve el bean de forma
opcional y la aplicación arranca sin variables de Mercado Pago.

Cancelar o cambiar de plan llama a la pasarela solo si la suscripción tiene
`gatewaySubscriptionId`. Si lo tiene y no hay pasarela configurada, falla con
502 (`GATEWAY_UNAVAILABLE`) antes de modificar nada.

La llamada remota se registra con `TransactionSynchronization.afterCommit`
(`GatewayOperations.*AfterCommit`): si la operación local falla o hace
rollback, la pasarela no se entera. Un error remoto después del commit no
deshace el cambio local; se loguea para conciliar (la conciliación llega con
PR1b). Sin transacción activa la llamada es directa.

Los tests nunca llaman a MP: usar `FakePaymentGateway` (en `src/test`).

Reglas para PR1b, que no se negocian:
- solo un pago aprobado, reconsultado en MP, activa o renueva
- nunca activar por `back_url` ni por el payload del webhook
- idempotencia por `webhook_events (provider, dedupe_key)` y por
  `payments (source, gateway_payment_id)`; `payments.applied` evita extender dos veces
- webhook: guardar primero, procesar después; si falla, `FAILED`, 200 y reintento por job

## Estados

`TRIALING`, `ACTIVE`, `PAST_DUE`, `SUSPENDED`, `CANCELED`.

| Estado | accessLevel |
|---|---|
| TRIALING, ACTIVE, PAST_DUE | FULL |
| SUSPENDED (NON_PAYMENT, ADMIN_READ_ONLY), CANCELED | READ_ONLY |
| SUSPENDED (ADMIN_BLOCK) | BLOCKED |
| sin suscripción | FULL, `known=false` |

El acceso se evalúa con reloj (`AccessPolicy`): si un job viene atrasado, el
acceso igual refleja el vencimiento.

`SubscriptionLifecycleJob` (cron `billing.jobs.lifecycle-cron`, zona
`billing.zone`) vence trials, cierra períodos y suspende por mora. Usa
`pg_try_advisory_xact_lock(1001)`, `FOR UPDATE SKIP LOCKED`, una transacción
por fila y eventos con `dedupe_key`. Tiene que ser idempotente.
Recorre los candidatos con cursor por id (`id > :afterId ORDER BY id`): una
fila que falla siempre o está bloqueada no se relee en la corrida ni frena el
lote; se reintenta en la corrida siguiente.

Las mutaciones por API toman la fila con `PESSIMISTIC_WRITE`. Hoy
`CONCURRENT_MODIFICATION` (409) solo lo dispara el `@Version` de Hibernate
(`ObjectOptimisticLockingFailureException`), en la práctica una carrera con el
job o entre réplicas que el bloqueo pesimista no cubrió. No hay
`expectedVersion` en la API: el cliente no puede pedir "aplicar solo si no
cambió".

Las acciones permitidas (`allowedActions`) y el acceso se calculan sobre el
estado efectivo de `AccessPolicy`, no sobre el estado guardado.

Pagar no anula una baja agendada: si `cancelAtPeriodEnd = true` y entra un
pago, el período se extiende pero la baja sigue agendada para el nuevo fin.
Es una decisión de negocio pendiente; no cambiarla sin definición explícita.

Vencimientos exclusivos: `trial_ends_at`, `current_period_end` y
`grace_ends_at` son el primer instante sin acceso (`now >= vencimiento`
vence). Los finales "por día" se guardan como las 00:00 AR del día siguiente
(`PeriodCalculator.startOfDayAfter`), y `NoticePolicy.daysUntil` cuenta hasta
el último día con acceso.

Día ancla: `billing_anchor_day` (1–31) se fija con el primer período pago y
mantiene el día de vencimiento sin deriva de fin de mes (31/01 → 28/02 →
31/03 → 30/04). Si es null, se deriva de `current_period_start`. Un calendario
nuevo (pago tras baja o suspensión, trial) se ancla en su propio inicio; un fin
explícito (pago manual con `periodEnd` o reactivación) ancla en el día de ese
fin.

## Errores

`GlobalExceptionHandler` devuelve `ErrorResponse` (`timestamp`, `status`,
`error`, `code`, `message`, `path`, `correlationId`).

| Excepción | HTTP |
|---|---|
| `SubscriptionNotFoundException`, `PlanNotFoundException`, `PlanPriceNotFoundException` | 404 |
| `InvalidSubscriptionTransitionException`, `SamePlanException`, `CheckoutNotAllowedException`, `Duplicate*Exception`, `PriceAlreadyEffectiveException`, `SubscriptionAdminSuspendedException` | 409 |
| `PlanWithoutPriceException`, `GatewayRejectedException` | 422 |
| validación, `InvalidBillingRequestException` | 400 |
| `GatewayUnavailableException` | 502 |
| `GatewayTimeoutException` | 504 |
| resto | 500 genérico |

Los fallos de la pasarela van a 502/504, nunca a 500.
`X-Correlation-ID` lo maneja `CorrelationIdFilter`.

## Base de datos

Base propia. `spring.jpa.hibernate.ddl-auto=validate` y
`spring.flyway.enabled=false`: las migraciones se aplican a mano.

Migraciones en `src/main/resources/db/migration`, de V1 a V7. La próxima es
`V8__descripcion.sql`. Nunca editar una migración commiteada.

`gateway_checkouts` (V5) y `webhook_events` (V6) existen en el esquema pero
todavía no tienen entidad JPA: llegan en PR1b.

Importes `NUMERIC(12,2)` y `BigDecimal` con escala 2 y `HALF_UP`. Nunca
`double`. Fechas `TIMESTAMPTZ` y `OffsetDateTime`. Zona de negocio:
`America/Argentina/Buenos_Aires`.

Los precios son inmutables: un cambio de precio es una fila nueva en
`plan_prices` con `valid_from` estrictamente futuro. Solo se puede borrar un
precio futuro.

Los importes que llegan por API (`ManualPaymentRequest`, `PlanPriceRequest`)
llevan `@Digits(integer = 10, fraction = 2)` y `Amounts.requirePositiveMoney`:
más de 2 decimales significativos es 400, nunca se redondea en silencio.

Pago manual: `reference` es obligatoria y es la clave de idempotencia por
tenant (`uq_payments_manual_reference`). Reintentar con la misma referencia y
el mismo importe devuelve el pago original con 200 sin aplicar otro período;
la primera vez es 201. Misma referencia con otro importe: 409
`DUPLICATE_PAYMENT`. Con suspensión administrativa: 409
`SUBSCRIPTION_ADMIN_SUSPENDED` sin persistir nada (hay que reactivar primero).

## Configuración

Obligatorias: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (mínimo 32
bytes), `BILLING_INTERNAL_API_KEY`.

Opcionales: `URL_FRONT`, `BILLING_PUBLIC_BASE_URL`, `BILLING_CHECKOUT_BACK_URL`,
`BILLING_TRIAL_DAYS` (14), `BILLING_TRIAL_PLAN_CODE` (PRO),
`BILLING_TRIAL_GRACE_DAYS` (3), `BILLING_GRACE_DAYS` (7),
`BILLING_CHECKOUT_EXPIRATION_HOURS` (48), `BILLING_JOBS_ENABLED` (true),
`SPRINGDOC_ENABLED` (true), `PORT`.

`SPRINGDOC_ENABLED` vale `true` por defecto para desarrollo local. En
producción configurarlo en `false`: apaga `/api-docs` y Swagger UI y la
cadena de seguridad deja de permitir esas rutas.

PR1b agrega `MP_ACCESS_TOKEN`, `MP_WEBHOOK_SECRET` y
`MP_WEBHOOK_REQUIRE_SIGNATURE`.

`JWT_SECRET` tiene que ser idéntico al de auth-service.

Los planes del seed nacen con `public_visible = false` y precios placeholder.
Publicarlos desde el backoffice solo después de cargar el precio real.

## Cambios

Antes de modificar:

1. Analizar la implementación existente (use case, port y adapter).
2. Identificar el impacto en la base.
3. Identificar las APIs afectadas y quién las consume (user-manager,
   pos-manager, purchase-manager, backoffice, front).
4. Si toca la máquina de estados, actualizar la tabla de transiciones y sus tests.
5. Si toca Mercado Pago, verificar contra la documentación oficial vigente.
6. Implementar respetando las capas.
7. Ejecutar los tests.

No refactorizar código no relacionado dentro del mismo cambio.

## Validación

```
./mvnw test
./mvnw -DskipTests package
```

En Windows: `.\mvnw.cmd test`.

Los tests son unitarios, sin base de datos ni Docker. No agregar un
`contextLoads` que necesite base dentro del gate; si hace falta, que sea `*IT`.

El mapeo JPA no está validado contra un PostgreSQL real por ningún test:
al tocar entidades o migraciones, levantar la app contra una base con V1–V7
aplicadas.

No considerar terminado un cambio si el gate falla. Al tocar transiciones,
`AccessPolicy`, `PeriodCalculator` o la seguridad, agregar test.
