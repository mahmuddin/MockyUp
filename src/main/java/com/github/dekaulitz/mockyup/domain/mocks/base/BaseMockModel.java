package com.github.dekaulitz.mockyup.domain.mocks.base;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dekaulitz.mockyup.db.entities.MockEntities;
import com.github.dekaulitz.mockyup.db.entities.UserMocksEntities;
import com.github.dekaulitz.mockyup.domain.mocks.vmodels.MockVmodel;
import com.github.dekaulitz.mockyup.infrastructure.configuration.security.AuthenticationProfileModel;
import com.github.dekaulitz.mockyup.infrastructure.errors.handlers.InvalidMockException;
import com.github.dekaulitz.mockyup.infrastructure.errors.handlers.NotFoundException;
import com.github.dekaulitz.mockyup.utils.JsonMapper;
import com.github.dekaulitz.mockyup.utils.MockHelper;
import com.github.dekaulitz.mockyup.utils.ResponseCode;
import com.github.dekaulitz.mockyup.utils.Role;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BaseMockModel {

    protected Logger log = LoggerFactory.getLogger(this.getClass());

    public void setSaveMockEntity(MockVmodel body, MockEntities mockEntities, AuthenticationProfileModel authenticationProfileModel) throws InvalidMockException {
        try {
            mockEntities.setSwagger(JsonMapper.mapper().writeValueAsString(body.getSpec()));
            mockEntities.setTitle(body.getTitle());
            mockEntities.setDescription(body.getDescription());
            parsingSpecToOpenApi(body, mockEntities);
            List<UserMocksEntities> users = new ArrayList<>();
            UserMocksEntities creator = new UserMocksEntities();
            creator.setUserId(authenticationProfileModel.get_id());
            creator.setAccess(Role.MOCKS_READ_WRITE.name());
            users.add(creator);
            if (body.getUsers() != null) {
                body.getUsers().forEach(userMocks -> {
                    UserMocksEntities user = new UserMocksEntities();
                    user.setAccess(userMocks.getAccess());
                    user.setUserId(userMocks.getUserId());
                    users.add(user);
                });
            }
            mockEntities.setUsers(users);
        } catch (JsonProcessingException e) {
            throw new InvalidMockException(ResponseCode.INVALID_MOCKUP_STRUCTURE, e);
        }

    }

    private void parsingSpecToOpenApi(MockVmodel body, MockEntities mockEntities) throws JsonProcessingException {
        SwaggerParseResult result;
        result = new OpenAPIParser().readContents(JsonMapper.mapper().writeValueAsString(body.getSpec()), null, null);
        OpenAPI openAPI = result.getOpenAPI();
        Paths newPath = new Paths();
        openAPI.getPaths().forEach((s, pathItem) -> {
            newPath.put(s.replace("{", "*{"), pathItem);
        });
        openAPI.setPaths(newPath);
        mockEntities.setSpec(JsonMapper.mapper().writeValueAsString(openAPI));
    }

    public void setUpdateMockEntity(MockVmodel body, MockEntities mockEntities) throws InvalidMockException {
        try {
            mockEntities.setSwagger(JsonMapper.mapper().writeValueAsString(body.getSpec()));
            parsingSpecToOpenApi(body, mockEntities);
        } catch (JsonProcessingException e) {
            throw new InvalidMockException(ResponseCode.INVALID_MOCKUP_STRUCTURE, e);
        }

    }

    /**
     * @param pathItem         {@link PathItem}
     * @param request          {@link HttpServletRequest}
     * @param body             {@link String}
     * @param openApiRoutePath {@link String[]}[swagger_path_contract] that mapped with [contract_path_that_want_to_mock]
     * @param paths            {@link String[]} [contract_path_that_want_to_mock]
     * @param components       {@link Components} components from open api
     * @return MockHelper {@link MockHelper} the response from mock response
     * @throws NotFoundException            if there no mock response detected
     * @throws JsonProcessingException      if parsing body was fail
     * @throws InvalidMockException         if mock response is invalid
     * @throws UnsupportedEncodingException if encoding query string is fail
     */
    public MockHelper renderingMockResponse(PathItem pathItem, HttpServletRequest request, String body, String[] openApiRoutePath, String[] paths, Components components)
            throws NotFoundException, JsonProcessingException, InvalidMockException, UnsupportedEncodingException {
        MockHelper mock = null;

        //parsing pathitem into JsonNode
        //its because from the pathItem you its more easier for geting type of method request that wanted
        JsonNode jsonNode = JsonMapper.mapper().valueToTree(pathItem);

        //find from node that matched with request method and convert into Operation
        Operation ops = JsonMapper.mapper().treeToValue(jsonNode.get(request.getMethod().toLowerCase()), Operation.class);

        //if there is no request method that matched with operation method bas on the [swagger_path_contract]
        if (ops.getExtensions() == null) throw new NotFoundException(ResponseCode.MOCKUP_NOT_FOUND);

        //get custom extension for mock response from operation with extension [x-examples]
        Map<String, Object> examples = (Map<String, Object>) ops.getExtensions().get(MockHelper.X_EXAMPLES);
        if (examples == null) throw new NotFoundException(ResponseCode.MOCKUP_NOT_FOUND);

        //iterate [x-examples] that available from operation extensions
        for (Map.Entry<String, Object> extension : examples.entrySet()) {
            /*
             *  for the mock it will responding by the order
             *  1. path
             *  2. query
             *  3. headers
             *  4. body
             *  5. default
             *  if the mock found with the expected criteria
             *  will stop the other search and returning the response
             */

            //get mock response from path
            if (extension.getKey() == MockHelper.X_PATH) {
                mock = MockHelper.generateResponsePath((List<Map<String, Object>>) extension.getValue(), openApiRoutePath, paths, components);
                if (mock != null) return mock;
            }

            //get mock from query string
            if (extension.getKey() == MockHelper.X_QUERY) {
                mock = MockHelper.generateResponseQuery(request, (List<Map<String, Object>>) extension.getValue(), components);
                if (mock != null) return mock;
            }

            //checking the mock from the headers
            if (extension.getKey() == MockHelper.X_HEADERS) {
                mock = MockHelper.generateResponseHeader(request, (List<Map<String, Object>>) extension.getValue(), components);
                if (mock != null) return mock;
            }

            //checking the mock from the boyd
            if (extension.getKey() == MockHelper.X_BODY) {
                mock = MockHelper.generateResponseBody(request, (List<Map<String, Object>>) extension.getValue(), body, components);
                if (mock != null) return mock;
            }

            //if there is no mock defined on path,header,query and body but default was defined
            if (extension.getKey() == MockHelper.X_DEFAULT) {
                mock = MockHelper.generateResponseDefault((Map<String, Object>) extension.getValue(), components);
                if (mock != null) return mock;
            }
        }
        return mock;
    }
}
