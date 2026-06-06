# Información para Artículo Medium — camel-mcp-gateway

> **Título sugerido**: "Construye un Gateway REST-to-MCP con Apache Camel y Spring Boot para dar superpoderes a tus Agentes de IA"

---

## 1. Resumen Ejecutivo (TL;DR)

`camel-mcp-gateway` es un servidor MCP (Model Context Protocol) configurable que expone cualquier REST API como herramientas consumibles por agentes de IA. Está construido con Apache Camel 4 + Spring Boot 3. Basta con declarar la URL base y el spec OpenAPI de cada servicio backend para que el gateway genere automáticamente N herramientas MCP listas para usar desde Claude, GPT, Llama o cualquier cliente MCP.

**Stack**: Java 17, Apache Camel 4.18.2, Spring Boot 3.5.13, swagger-parser 2.1.24, Undertow, Lombok.

---

## 2. ¿Qué problema resuelve?

Los LLMs necesitan herramientas para interactuar con sistemas externos. Sin un gateway MCP, cada API REST requiere código a medida para:

- Parsear el spec OpenAPI
- Serializar/deserializar requests JSON-RPC
- Enrutar dinámicamente a cada backend
- Manejar transporte SSE

Con este gateway, todo se vuelve **declarativo** en un archivo YAML. El agente de IA gana acceso inmediato a N APIs sin escribir una línea de integración.

---

## 3. Arquitectura General

```
┌─────────────────────────────────────────────────────────────┐
│                  Cliente MCP (Claude, GPT, etc)              │
│   POST /mcp  (JSON-RPC HTTP)    │   GET /mcp/sse (SSE)      │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                   camel-mcp-gateway                          │
│                                                              │
│  ┌──────────────┐   ┌──────────────┐   ┌─────────────────┐  │
│  │ REST DSL     │──▶│ Procesadores  │──▶│ Rutas Dinámicas │  │
│  │ POST /mcp    │   │ list / call   │   │ direct:mcp:tool  │  │
│  └──────────────┘   └──────────────┘   └────────┬─────────┘  │
│                                                  │            │
│  ┌──────────────┐   ┌──────────────┐             │            │
│  │ SSE Stream   │──▶│ SseSession   │             │            │
│  │ GET /mcp/sse │   │ Manager      │             │            │
│  └──────────────┘   └──────────────┘             │            │
│                                                  │            │
│  ┌──────────────┐                                │            │
│  │ OpenAPI      │──▶ ToolRegistry                │            │
│  │ Loader       │    (tools/list cache)          │            │
│  └──────────────┘                                │            │
└──────────────────────────────────────────────────┼────────────┘
                                                    │
┌───────────────────────────────────────────────────┼────────────┐
│                     Backends (N servicios)                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │ restful-api  │  │ pokeapi.co   │  │ otros...     │        │
│  └──────────────┘  └──────────────┘  └──────────────┘        │
└────────────────────────────────────────────────────────────────┘
```

**Principio clave**: el gateway no conoce los backends en compilación. Los descubre en tiempo de ejecución leyendo los specs OpenAPI configurados en `application.yml`.

---

## 4. Componentes Clave del Proyecto

### 4.1 Modelos MCP (`model/mcp/`)

Tres clases que implementan el protocolo JSON-RPC 2.0:

- **`McpRequest`** — `{ jsonrpc, method, params, id }` con anotaciones Jackson
- **`McpResponse`** — success/error con `McpError`, `ToolListResult`, `ToolCallResult`, `ContentItem`
- **`ToolDefinition`** — schema de herramienta con `name`, `description`, `inputSchema` (type + properties + required)

### 4.2 Carga de OpenAPI (`openapi/OpenApiSpecLoader.java`)

Soporta 3 orígenes para los specs:

| Origen | Configuración | Ejemplo |
|--------|--------------|---------|
| URL remota | `openapi-url` | `https://ejemplo.com/openapi.json` |
| Classpath | `openapi-path` | `classpath:openapi/spec.yaml` |
| Archivo local | `openapi-path` | `file:/etc/gateway/spec.json` |

Usa `OpenAPIV3Parser` de swagger-parser para parsear. Itera sobre `paths → httpMethod → operation` y construye objetos `ApiOperation` con:
- `serviceName`, `operationId`, `httpMethod`, `path`
- `parameters` (name, in, required, type, description)
- `hasRequestBody`

### 4.3 Registry de Herramientas (`registry/ToolRegistry.java`)

Almacena en un `ConcurrentHashMap<String, ToolEntry>` donde `ToolEntry` es un **Java record** (Java 16+):

```java
public record ToolEntry(ToolDefinition definition, ApiOperation operation) {}
```

- `registerOperation(ApiOperation)` → crea el nombre `{service}_{operationId}`, construye el `inputSchema` mapeando tipos OpenAPI → JSON Schema, y almacena la entrada.
- `listAllTools()` → devuelve todas las `ToolDefinition` para `tools/list`
- `getTool(name)` → lookup individual para `tools/call`

### 4.4 Rutas Camel

#### McpEndpointRoute (REST DSL)

```java
rest("/mcp").post()
    .consumes("application/json")
    .produces("application/json")
    .to("direct:mcpRouter");
```

Usa `camel-servlet-starter` montado sobre Undertow. El `ChoiceDefinition` enruta según el header `method` extraído del body JSON deserializado como `McpRequest`.

```java
.choice()
    .when(header("method").isEqualTo("tools/list"))
        .process(toolsListProcessor)
    .when(header("method").isEqualTo("tools/call"))
        .to("direct:mcpToolsCall")
    .otherwise()
        // error JSON-RPC code -32601
```

#### DynamicRouteManager (rutas dinámicas)

Crea UNA ruta Camel por operación OpenAPI en `@PostConstruct`:

```java
from("direct:mcp-tool:{name}")
    .process(/* construye URL completa + body */)
    .toD("${header.fullUrl}")
    .convertBodyTo(String.class)
    .process(/* valida HTTP status */)
```

Usa `producerTemplate.requestBodyAndHeaders()` desde el procesador `ToolsCallProcessor` para invocar estas rutas.

### 4.5 Procesadores

- **`ToolsListProcessor`** (implementa `Processor`): extrae el body como `McpRequest`, consulta `toolRegistry.listAllTools()`, arma `McpResponse.success(id, Map.of("tools", ...))`.
- **`ToolsCallProcessor`** (POJO con método `process(McpRequest)`): extrae `params.name` y `params.arguments`, busca el `ToolEntry`, invoca la ruta dinámica via ProducerTemplate, captura errores y devuelve `McpResponse`.

### 4.6 Transporte SSE (`sse/`)

- **`SseController`**:
  - `GET /mcp/sse` → crea `SseEmitter` con timeout infinito, envía evento `endpoint` con la URL `/mcp/message?sessionId=...`
  - `POST /mcp/message?sessionId=...` → recibe JSON-RPC, delega en `handleMcpMethod()`, envía respuesta por el SSE del sessionId
- **`SseSessionManager`**: `ConcurrentHashMap<String, SseEmitter>` con métodos `createSession`, `getSession`, `removeSession`

### 4.7 Configuración (`application.yml`)

```yaml
app:
  mcp-gateway:
    services:
      - name: objects-service
        base-url: https://api.restful-api.dev
        openapi-path: classpath:openapi/restful-api.json
      - name: pokemons
        base-url: https://pokeapi.co/api/v2
        openapi-path: classpath:openapi/pokeapi.yaml
```

---

## 5. Flujo Completo de una llamada tools/call

```
1. Cliente envía POST /mcp con body JSON-RPC
2. Camel REST DSL recibe la request
3. Jackson unmarshal → McpRequest
4. Choice router: header("method") == "tools/call"
5. → direct:mcpToolsCall
6. ToolsCallProcessor.process(McpRequest):
   a. Extrae params.name = "pokemons_getPokemonByName"
   b. Busca en ToolRegistry → obtiene ToolEntry
   c. Extrae params.arguments = { name: "ditto" }
   d. producerTemplate.requestBodyAndHeaders("direct:mcp-tool:pokemons_getPokemonByName", arguments, headers)
7. Ruta dinámica:
   a. Resuelve path: /pokemon/{name} → /pokemon/ditto
   b. Construye URL: https://pokeapi.co/api/v2/pokemon/ditto
   c. toD("${header.fullUrl}") → HTTP GET
   d. Convierte respuesta a String
   e. Valida HTTP status < 400
8. ToolsCallProcessor construye McpResponse.content[].text
9. Marshall JSON → response al cliente
```

---

## 6. Decisiones Técnicas Relevantes

### ¿Por qué camel-servlet-starter y no camel-platform-http-starter?

El transporte SSE requiere un `SseController` de Spring MVC (`@RestController` con `SseEmitter`). Con `camel-servlet-starter`, el servlet de Camel y Spring MVC coexisten en el mismo Undertow. `camel-platform-http` no se integra con los controladores Spring MVC.

### ¿Por qué Undertoy y no Tomcat?

- Undertow permite `SseEmitter` con timeout infinito (`0L`)
- Menor huella de memoria
- Manejo eficiente de conexiones concurrentes (SSE)

### ¿Por qué construir la URL completa en el procesador en vez de usar Exchange.HTTP_PATH?

El header `Exchange.HTTP_PATH` no se aplica correctamente cuando la URL base ya contiene un path (ej: `https://pokeapi.co/api/v2`). Construir la URL completa y pasarla con `toD("${header.fullUrl}")` es más predecible.

### ¿Por qué records de Java?

`ToolEntry` como `record` da `equals()`, `hashCode()`, `toString()` y accessors (`definition()`, `operation()`) sin boilerplate. Requiere Java 16+.

### ¿Por qué deserializar directamente a McpRequest en el unmarshal?

Evita problemas de TypeConverter al pasar de Map→POJO entre procesadores. Jackson convierte el JSON directamente al tipo esperado.

---

## 7. Código Destacado para Mostrar en el Artículo

### 7.1 ToolEntry como record

```java
public record ToolEntry(ToolDefinition definition, ApiOperation operation) {}
```

### 7.2 Creación dinámica de rutas Camel

```java
camelContext.addRoutes(new RouteBuilder() {
    @Override
    public void configure() {
        from(directUri)
            .routeId(routeId)
            .process(exchange -> {
                // Construir URL completa con path + query
                String fullUrl = baseUrl + resolvedPath;
                if (!queryString.isEmpty()) {
                    fullUrl += "?" + queryString;
                }
                exchange.getIn().setHeader("fullUrl", fullUrl);
            })
            .toD("${header.fullUrl}")
            .convertBodyTo(String.class);
    }
});
```

### 7.3 Choice router con JSON-RPC

```java
.unmarshal().json(JsonLibrary.Jackson, McpRequest.class)
.process(exchange -> {
    McpRequest request = exchange.getIn().getBody(McpRequest.class);
    if (request != null) {
        exchange.getIn().setHeader("method", request.getMethod());
    }
})
.choice()
    .when(header("method").isEqualTo("tools/list"))
        .process(toolsListProcessor)
    .when(header("method").isEqualTo("tools/call"))
        .to("direct:mcpToolsCall")
    .otherwise()
        // error
.end();
```

---

## 8. Ejemplos de Uso (con respuestas reales)

### tools/list (7 herramientas registradas)
```
POST /mcp
→ { tools: [{ name: "objects-service_listObjects", ... }, ...] }
```

### tools/call — query params
```
POST /mcp { method: "tools/call", params: { name: "pokemons_listPokemon", arguments: { limit: 3 } } }
→ [ bulbasaur, ivysaur, venusaur ]
```

### tools/call — path params
```
POST /mcp { method: "tools/call", params: { name: "pokemons_getPokemonByName", arguments: { name: "ditto" } } }
→ { id: 132, name: "ditto", abilities: [...], ... }
```

### tools/call — POST con body
```
POST /mcp { method: "tools/call", params: { name: "objects-service_createObject", arguments: { body: { name: "test" } } } }
→ { id: "...", name: "test", createdAt: ... }
```

---

## 9. Casos de Uso Reales para el Artículo

| Escenario | APIs conectar | Qué puede hacer el agente |
|-----------|--------------|--------------------------|
| **DevOps** | Kubernetes, Docker Hub, StatusPage | Consultar pods, reiniciar servicios, verificar health |
| **Soporte** | Jira, Zendesk, Confluence | Crear tickets, buscar KB, consultar estado |
| **E-commerce** | Shopify, Mercado Pago,物流API | Ver inventario, procesar reembolsos, tracking |
| **Finanzas** | Yahoo Finance, ExchangeRate-API | Tipo de cambio, rendimiento de ETFs |
| **Datos** | OpenWeather, IP Geolocation, DB interna | Cruzar datos geo-climáticos en lenguaje natural |

---

## 10. Log de Errores y Soluciones (Incidentes durante el desarrollo)

| # | Problema | Causa | Solución |
|---|----------|-------|----------|
| 1 | records y switch no compilaban | Java 11 configurado en pom.xml | Cambiar `<release>` a 17 |
| 2 | ParseOptions en package incorrecto | swagger-parser cambió de paquete | Usar `io.swagger.v3.parser.core.models.ParseOptions` |
| 3 | OpenAPIParser no existe | API de swagger-parser v3 | Usar `OpenAPIV3Parser` en su lugar |
| 4 | `ToolEntry::getDefinition` incompatible | records Java generan accessor sin prefijo | Lambda `entry -> entry.definition()` |
| 5 | Camel 4.x requiere endChoice() entre when() | ChoiceDefinition encadenado incorrecto | Agregar `.endChoice()` entre bloques |
| 6 | Respuestas como `InputStreamCache@...` | Falta `convertBodyTo(String.class)` en ruta dinámica | Agregar `.convertBodyTo(String.class)` después de `.toD()` |
| 7 | Path no se aplicaba con URLs base con path | `Exchange.HTTP_PATH` no funciona con toD dinámico | Construir URL completa en process + `toD("${header.fullUrl}")` |

---

## 11. Métricas Finales

- **Clases Java**: 16
- **Herramientas MCP generadas**: 7 (desde 2 specs OpenAPI)
- **Rutas Camel creadas**: 9 (1 rest + 2 direct principales + 7 dinámicas)
- **Líneas de código**: ~650 (sin contar tests)
- **Tests**: 2/2 exitosos (context-load + routes)
- **Tiempo de compilación**: ~4s
- **Tiempo de startup**: ~3s

---

## 12. Próximos Pasos / Mejoras Potenciales

- [ ] **Hot-reload de rutas**: detectar cambios en los specs sin reiniciar
- [ ] **Autenticación por servicio**: API keys/oauth configurable por backend
- [ ] **Rate limiting**: control de throttling por herramienta
- [ ] **Caching**: cachear respuestas GET con TTL configurable
- [ ] **OpenAPI spec caching**: cachear specs remotos con ETag
- [ ] **MCP Prompts**: exponer plantillas de prompts además de tools
- [ ] **Docker image**: Dockerfile multi-stage + docker-compose
- [ ] **CI/CD**: GitHub Actions con compilación y tests
- [ ] **Más formatos de spec**: soporte para GraphQL (SDL → MCP)
- [ ] **Observabilidad**: métricas Micrometer por herramienta llamada

---

## 13. Recursos y Referencias

- [Model Context Protocol (MCP) — especificación](https://spec.modelcontextprotocol.io/)
- [Apache Camel 4.x — Documentación](https://camel.apache.org/manual/)
- [Spring Boot 3.5 — Reference](https://docs.spring.io/spring-boot/docs/3.5.x/reference/html/)
- [swagger-parser v3 — GitHub](https://github.com/swagger-api/swagger-parser)
- [restful-api.dev — API de demostración](https://api.restful-api.dev/)
- [PokéAPI — API pública de Pokémon](https://pokeapi.co/)
- [Proyecto completo en GitHub](https://github.com/tu-usuario/camel-mcp-gateway)
