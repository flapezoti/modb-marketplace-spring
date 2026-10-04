# vmodb-marketplace

A small VMODB implementation of two services from the Online Marketplace benchmark: **Product**
and **Cart**, specifically Cart's `product_replicas` table, a read replica of Product's catalog
data. Cart stays up to date only by consuming Product's outbound events (`PRODUCT_UPDATED`,
`PRICE_UPDATED`), so the example shows how VMODB's coordinator keeps a replica consistent: it
delivers events to a VMS in transaction-id order, and no ordering key, polling or client-side
merge logic is needed.

Both VMSes are Spring Boot applications configured through `application-*.yml` and started with
[`vmodb-spring-starter`](../vmodb-spring-starter).

## Modules

- `common` — shared `Constants` and the event/input classes (`UpdateProduct`/`ProductUpdated`,
  `PriceUpdate`/`PriceUpdated`).
- `product` — owns the `products` table. Two transactions: `updateProduct` (create or update,
  emits `PRODUCT_UPDATED`) and `updateProductPrice` (always emits `PRICE_UPDATED`, but applies the
  price locally only if `priceUpdate.version` matches the product's current version).
  `IProductRepository` and `ProductService` are exposed as typed Spring beans in `Main`, backing
  `ProductQueryController` (`GET /products/{sellerId}/{productId}`, `GET /system/info`) — a
  read API over Spring MVC, separate from VMODB's own raw HTTP handler.
- `cart` — owns `product_replicas`. Applies the same version check to `PRICE_UPDATED`, so a
  stale or conflicting price update is rejected identically on both sides. Same bean/controller
  pattern as `product`: `IProductReplicaRepository`/`CartService` beans back
  `ReplicaQueryController` (`GET /replicas/{sellerId}/{productId}`, `GET /system/info`).
- `test`:
  - `CartProductPriceOrderingTest` — boots both VMSes and an embedded `Coordinator` with two
    two-node DAGs (`update_product`, `update_price`; Product to Cart), then checks that
    (1) creating a product also creates Cart's replica, (2) a matching-version price update is
    applied on both sides, and (3) a conflicting-version price update is emitted by Product but
    rejected on both sides. Verifies state in-process, through each `Main`'s exposed beans.
  - `SpringQueryApiTest` — the same three-step scenario, verified instead through the
    `ProductQueryController`/`ReplicaQueryController` HTTP endpoints, while the coordinator
    session is active.
  - `CoordinatorTestSupport` — the coordinator/DAG bootstrap shared by both test classes.

## Building and testing

The VMODB modules (`modb-api`, `modb-common`, `sdk-embed`, `sdk-core`, `web_common`,
`coordinator`, version `1.0-SNAPSHOT`) and `vmodb-spring-starter` must be installed in the local
Maven repository first, for example:

```
# in the VMODB source tree
JAVA_HOME=<jdk21> mvn -pl modb-api,modb-common,sdk-embed,sdk-core,web_common,coordinator -am \
    clean install -DskipTests=true

# in vmodb-spring-starter
JAVA_HOME=<jdk21> mvn clean install

# then, here
JAVA_HOME=<jdk21> mvn clean test
```

Requires JDK 21 (VMODB compiles with `--enable-preview` for memory-segment APIs).

## Notes

- **Querying a VMS over plain HTTP while a coordinator session is active returns an empty
  reply** (checked with `curl` and Java's `HttpClient`) — but only through VMODB's own raw HTTP
  handler (`ProductHttpHandler`/`CartHttpHandler` in each `Main`). The Spring MVC endpoints
  (`ProductQueryController`/`ReplicaQueryController`, on their own `server.port`) do not go
  through that handler and are unaffected — `SpringQueryApiTest` exercises them specifically
  while the coordinator session is live. `CartProductPriceOrderingTest` still verifies state
  in-process, through the beans and repositories exposed as static fields on each `Main`.
- **`VmsApplication.getService(name)` is keyed by canonical class name, not the
  `@Microservice("...")` annotation value.** `VmsMetadataLoader.loadMicroserviceClasses()` does
  `loadedMicroserviceInstances.put(clazz.getCanonicalName(), vmsInstance)`. Use
  `ProductService.class.getName()` (see `VmodbBootstrap.service(...)` in `vmodb-spring-starter`
  and the `productService`/`cartService` `@Bean` methods here), not the string passed to
  `@Microservice`.
- **`IEntity<PK>.getId()`'s default implementation throws `UnsupportedOperationException`**, and
  neither `Product` nor `ProductReplica` overrides it. Jackson's default bean introspection
  auto-detects that public getter-shaped method as an "id" property and calls it when
  serializing to JSON, which surfaced as a 500 from `ProductQueryController`/
  `ReplicaQueryController` (`JsonMappingException: (was UnsupportedOperationException) ...
  through reference chain Product["id"]`). Both entity classes carry
  `@JsonIgnoreProperties({"id"})` for this reason.
- **VMODB's repository-proxy class generation cannot run twice in the same classloader.** Once
  a second test class (`SpringQueryApiTest`) also booted `product`/`cart`, it failed — in the
  same JVM as `CartProductPriceOrderingTest` — with `IllegalStateException: Cannot inject
  already loaded type: class ProductRepositoryImpl`. `test/pom.xml` sets Surefire's
  `<reuseForks>false</reuseForks>` so every test class that boots a VMS gets its own JVM — add
  a third such test class and it will need this too, not just a symptom to work around once.
- **Read with `VMS.lastTidFinished()`, not tid 0.** `beginTransaction(0, 0, 0, true)` after
  coordinator transactions have advanced the tid counter opens a pre-commit snapshot and returns
  `null` or stale data. Pass `lastTidFinished()` as both `tid` and `lastTid`.
- **The `VmsApplication.build(...)` call stays in each module's `Main`.** VMODB keeps only the
  `@Microservice` classes in the direct caller's package, so the call must be made from the
  service's own package (see `vmodb-spring-starter`).
- **Both VMSes run in one JVM in the test**, so their config files have distinct names
  (`application-product.yml`, `application-cart.yml`), selected with
  `System.setProperty("spring.config.name", ...)` in each `Main.main()`. Otherwise Spring Boot
  loads the first `application.yml` on the classpath for both.
- Other VMODB constraints hit while building this: a VMS cannot appear twice in one
  `TransactionDAG`; one `@Event` class must map to one queue name per VMS; `lookupByKey` does not
  work with a bare `String` primary key; strings in tables are truncated to 32 characters; keys
  are identified by `hashCode()` only; state did not survive a VMS restart in testing.
