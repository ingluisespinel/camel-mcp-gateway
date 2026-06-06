package com.lespinel.camel.mcp.openapi;

import com.lespinel.camel.mcp.config.ServiceDefinition;
import com.lespinel.camel.mcp.model.ApiOperation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class OpenApiSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecLoader.class);

    public List<ApiOperation> load(ServiceDefinition service) {
        String specContent = readSpecContent(service);
        if (specContent == null) {
            log.warn("Could not load OpenAPI spec for service: {}", service.getName());
            return List.of();
        }
        return parse(specContent, service);
    }

    String readSpecContent(ServiceDefinition service) {
        if (service.getOpenapiUrl() != null && !service.getOpenapiUrl().isBlank()) {
            return readFromUrl(service.getOpenapiUrl());
        }
        if (service.getOpenapiPath() != null && !service.getOpenapiPath().isBlank()) {
            return readFromPath(service.getOpenapiPath());
        }
        log.warn("No openapi-url or openapi-path configured for service: {}", service.getName());
        return null;
    }

    String readFromUrl(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Loaded OpenAPI spec from URL: {}", url);
                return response.body();
            }
            log.warn("HTTP {} loading OpenAPI spec from URL: {}", response.statusCode(), url);
            return null;
        } catch (Exception e) {
            log.warn("Failed to load OpenAPI spec from URL: {} - {}", url, e.getMessage());
            return null;
        }
    }

    String readFromPath(String path) {
        try {
            if (path.startsWith("classpath:")) {
                String classpathResource = path.substring("classpath:".length());
                var resource = getClass().getClassLoader().getResourceAsStream(classpathResource);
                if (resource == null) {
                    log.warn("Classpath resource not found: {}", classpathResource);
                    return null;
                }
                String content = new String(resource.readAllBytes());
                log.info("Loaded OpenAPI spec from classpath: {}", classpathResource);
                return content;
            }
            if (path.startsWith("file:")) {
                Path filePath = Paths.get(URI.create(path));
                String content = Files.readString(filePath);
                log.info("Loaded OpenAPI spec from file: {}", filePath);
                return content;
            }
            Path filePath = Paths.get(path);
            if (Files.exists(filePath)) {
                String content = Files.readString(filePath);
                log.info("Loaded OpenAPI spec from file: {}", path);
                return content;
            }
            log.warn("OpenAPI spec path not found: {}", path);
            return null;
        } catch (Exception e) {
            log.warn("Failed to read OpenAPI spec from path: {} - {}", path, e.getMessage());
            return null;
        }
    }

    List<ApiOperation> parse(String specContent, ServiceDefinition service) {
        try {
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            options.setFlatten(true);

            OpenAPI openAPI = new OpenAPIV3Parser().readContents(specContent, null, options).getOpenAPI();
            if (openAPI == null) {
                log.warn("Failed to parse OpenAPI spec for service: {}", service.getName());
                return List.of();
            }

            List<ApiOperation> operations = new ArrayList<>();
            String baseUrl = resolveBaseUrl(openAPI, service.getBaseUrl());

            if (openAPI.getPaths() != null) {
                for (var pathEntry : openAPI.getPaths().entrySet()) {
                    String path = pathEntry.getKey();
                    PathItem pathItem = pathEntry.getValue();

                    Map<PathItem.HttpMethod, Operation> methodMap = new LinkedHashMap<>();
                    if (pathItem.getGet() != null) methodMap.put(PathItem.HttpMethod.GET, pathItem.getGet());
                    if (pathItem.getPost() != null) methodMap.put(PathItem.HttpMethod.POST, pathItem.getPost());
                    if (pathItem.getPut() != null) methodMap.put(PathItem.HttpMethod.PUT, pathItem.getPut());
                    if (pathItem.getDelete() != null) methodMap.put(PathItem.HttpMethod.DELETE, pathItem.getDelete());
                    if (pathItem.getPatch() != null) methodMap.put(PathItem.HttpMethod.PATCH, pathItem.getPatch());

                    for (var methodEntry : methodMap.entrySet()) {
                        String httpMethod = methodEntry.getKey().name();
                        Operation operation = methodEntry.getValue();

                        String operationId = operation.getOperationId();
                        if (operationId == null || operationId.isBlank()) {
                            operationId = service.getName() + "_" + httpMethod.toLowerCase() + "_" + path.replaceAll("[\\/{}\\-]", "_");
                        }

                        List<ApiOperation.ParameterInfo> params = new ArrayList<>();
                        if (operation.getParameters() != null) {
                            for (Parameter param : operation.getParameters()) {
                                String type = "string";
                                if (param.getSchema() != null && param.getSchema().getType() != null) {
                                    type = param.getSchema().getType();
                                }
                                params.add(ApiOperation.ParameterInfo.builder()
                                        .name(param.getName())
                                        .in(param.getIn())
                                        .required(param.getRequired() != null && param.getRequired())
                                        .type(type)
                                        .description(param.getDescription())
                                        .build());
                            }
                        }

                        boolean hasRequestBody = operation.getRequestBody() != null;

                        operations.add(ApiOperation.builder()
                                .serviceName(service.getName())
                                .operationId(operationId)
                                .httpMethod(httpMethod)
                                .path(path)
                                .summary(operation.getSummary())
                                .description(operation.getDescription())
                                .parameters(params)
                                .hasRequestBody(hasRequestBody)
                                .build());
                    }
                }
            }

            log.info("Parsed {} operations from OpenAPI spec for service: {}", operations.size(), service.getName());
            return operations;

        } catch (Exception e) {
            log.error("Error parsing OpenAPI spec for service: {} - {}", service.getName(), e.getMessage());
            return List.of();
        }
    }

    private String resolveBaseUrl(OpenAPI openAPI, String configuredBaseUrl) {
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            Server server = openAPI.getServers().get(0);
            if (server.getUrl() != null && !server.getUrl().isBlank()) {
                return server.getUrl();
            }
        }
        return configuredBaseUrl;
    }
}
