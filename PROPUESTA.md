# Propuesta: camel-mcp-gateway — REST-to-MCP Server Configurable

## 1. Objetivo

Construir un **servidor MCP (Model Context Protocol)** basado en **Apache Camel + Spring Boot** que permita exponer cualquier REST API como herramientas consumibles por agentes de IA (LLMs), configurando únicamente la URL base y el spec OpenAPI de cada servicio backend.

## 2. Problema que Resuelve

Los agentes de IA (Claude, GPT, Llama) necesitan **herramientas** para interactuar con el mundo real. Cada REST API existente requiere un wrapper manual para ser expuesta como herramienta MCP. Con `camel-mcp-gateway`, este proceso se vuelve **declarativo**: definís el servicio y su OpenAPI spec, y el gateway genera automáticamente las N herramientas MCP correspondientes.

## 3. Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│                    Cliente MCP (LLM)                          │
│          POST /mcp  (JSON-RPC HTTP)                          │
│          GET  /mcp/sse  (SSE Transport)                      │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                   camel-mcp-gateway                           │
│                                                               │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐  │
│  │ JSON-RPC     │──▶│ Procesadores  │──▶│ Rutas Dinámicas  │  │
│  │ POST /mcp    │   │ list / call   │   │ direct:mcp:{id}  │  │
│  └──────────────┘   └──────────────┘   └────────┬─────────┘  │
│                                                   │           │
│  ┌──────────────┐   ┌──────────────┐             │           │
│  │ SSE Stream   │──▶│ SseSession   │             │           │
│  │ GET /mcp/sse │   │ Manager      │             │           │
│  └──────────────┘   └──────────────┘             │           │
│                                                   │           │
│  ┌──────────────┐                                │           │
│  │ OpenAPI      │──▶ ToolRegistry                │           │
│  │ Loader       │    (tools/list cache)          │           │
│  └──────────────┘                                │           │
└──────────────────────────────────────────────────┼───────────┘
                                                    │
┌───────────────────────────────────────────────────┼───────────┐
│                      Backends (N servicios)                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ objects      │  │ pokemons     │  │ otros...     │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
└──────────────────────────────────────────────────────────────┘
```

## 4. Transportes Soportados

### 4.1 JSON-RPC sobre HTTP
- `POST /mcp` — Request/Response síncrono
- Body: `{ "jsonrpc": "2.0", "method": "tools/list" | "tools/call", "params": {...}, "id": 1 }`

### 4.2 SSE (Server-Sent Events) — Estándar MCP
- `GET /mcp/sse` — Conexión SSE persistente
- Servidor envía evento `endpoint` con URL para POSTear mensajes
- `POST /mcp/message?sessionId=xxx` — Envía mensajes JSON-RPC
- Respuestas llegan por la conexión SSE

## 5. Configuración de Servicios

Se soportan **3 fuentes** para specs OpenAPI:

```yaml
app:
  mcp-gateway:
    services:
      - name: objects-service
        base-url: https://api.restful-api.dev
        openapi-url: https://api.restful-api.dev/openapi.json

      - name: pokemons
        base-url: https://pokeapi.co/api/v2
        openapi-path: classpath:openapi/pokeapi.yaml

      - name: mi-servicio-interno
        base-url: http://localhost:8081/api
        openapi-path: file:/etc/gateway/specs/mi-servicio.json
```

| Origen | Propiedad | Ejemplo |
|--------|-----------|---------|
| URL remota | `openapi-url` | `https://ejemplo.com/openapi.json` |
| Classpath | `openapi-path` | `classpath:openapi/spec.yaml` |
| Archivo local | `openapi-path` | `file:/ruta/absoluta/spec.json` |

## 6. Flujo de Datos

### tools/list
```
POST /mcp { method: "tools/list" }
  → ToolRegistry.devuelve todas las herramientas
  → { tools: [{ name, description, inputSchema }, ...] }
```

### tools/call
```
POST /mcp { method: "tools/call", params: { name: "pokemons_getPokemonByName", arguments: { name: "ditto" } } }
  → DynamicRouteManager resuelve la ruta
  → toD(baseUrl + path?bridgeEndpoint=true)
  → Response MCP con el resultado
```

## 7. Stack Tecnológico

| Componente | Versión |
|------------|---------|
| Java | 11+ |
| Apache Camel | 4.18.2 |
| Spring Boot | 3.5.13 |
| swagger-parser | 2.1.24 |
| Undertow (Spring Boot) | — |
| Lombok | 1.18.46 |

## 8. Estructura del Proyecto

```
camel-mcp-gateway/
├── pom.xml
├── PROPUESTA.md
├── SEGUIMIENTO.md
└── src/main/java/com/lespinel/camel/mcp/
    ├── CamelMcpGatewayApplication.java
    ├── config/
    │   ├── McpGatewayConfig.java
    │   └── ServiceDefinition.java
    ├── model/
    │   ├── mcp/
    │   │   ├── McpRequest.java
    │   │   ├── McpResponse.java
    │   │   └── ToolDefinition.java
    │   └── ApiOperation.java
    ├── openapi/
    │   ├── OpenApiSpecLoader.java
    │   └── OpenApiParser.java
    ├── registry/
    │   └── ToolRegistry.java
    ├── routes/
    │   ├── McpEndpointRoute.java
    │   ├── DynamicRouteManager.java
    │   └── processors/
    │       ├── ToolsListProcessor.java
    │       └── ToolsCallProcessor.java
    ├── sse/
    │   ├── SseController.java
    │   └── SseSessionManager.java
    └── commons/
        ├── GatewayException.java
        └── ErrorController.java
```

## 9. Entregables

1. Servidor MCP funcional con dos transportes (HTTP + SSE)
2. Carga de specs OpenAPI desde URL, classpath y archivo local
3. Generación automática de herramientas MCP a partir de specs
4. Enrutamiento dinámico a backends via Camel
5. Tests de integración
6. Documentación de uso
