/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.aws.wag;

import static org.apache.commons.lang3.StringUtils.trimToEmpty;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.http.AmazonHttpClient;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.aws.AbstractAWSCredentialsProviderProcessor;
import org.apache.nifi.processors.aws.AbstractAWSProcessor;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayClient;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayClientBuilder;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayRequest;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayRequestBuilder;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayResponse;

/**
 * This class is the base class for invoking aws gateway api endpoints
 */
public abstract class AbstractAWSGatewayApiProcessor extends AbstractAWSCredentialsProviderProcessor<GenericApiGatewayClient> {

    private volatile Set<String> dynamicPropertyNames = new HashSet<>();
    private volatile Pattern regexAttributesToSend = null;
    private volatile AmazonHttpClient providedClient = null;

    public final static String STATUS_CODE = "aws.gateway.api.status.code";
    public final static String STATUS_MESSAGE = "aws.gateway.api.status.message";
    public final static String RESOURCE_NAME_ATTR = "aws.gateway.api.resource";
    public final static String ENDPOINT_ATTR = "aws.gateway.api.endpoint";
    public final static String TRANSACTION_ID = "aws.gateway.api.tx.id";
    public final static String EXCEPTION_CLASS = "aws.gateway.api.java.exception.class";
    public final static String EXCEPTION_MESSAGE = "aws.gateway.api.java.exception.message";

    protected static final String REL_RESPONSE_NAME = "Response";
    protected static final String REL_SUCCESS_REQ_NAME = "Original";
    protected static final String REL_RETRY_NAME = "Retry";
    protected static final String REL_NO_RETRY_NAME = "No Retry";
    protected static final String REL_FAILURE_NAME = "Failure";

    public AbstractAWSGatewayApiProcessor(){}

    public AbstractAWSGatewayApiProcessor(AmazonHttpClient client) {
        providedClient = client;
    }


    // Set of flowfile attributes which we generally always ignore during
    // processing, including when converting http headers, copying attributes, etc.
    // This set includes our strings defined above as well as some standard flowfile
    // attributes.
    public static final Set<String> IGNORED_ATTRIBUTES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(STATUS_CODE, STATUS_MESSAGE, RESOURCE_NAME_ATTR, TRANSACTION_ID,
                      "uuid", "filename", "path")));

    public static final PropertyDescriptor AWS_API_KEY = new PropertyDescriptor
            .Builder().name("Amazon Gateway Api Key")
            .description("The API Key")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor AWS_GATEWAY_API_ENDPOINT = new PropertyDescriptor
            .Builder().name("Amazon Gateway Api Endpoint")
            .description("The Api Endpoint")
            .required(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    // we use our own region, because the way the base sets the region after the client is created
    // resets the endpoint and breaks everything
    public static final PropertyDescriptor AWS_GATEWAY_API_REGION = new PropertyDescriptor.Builder()
        .name("Amazon Gateway Api Region")
        .required(true)
        .allowableValues(AbstractAWSProcessor.getAvailableRegions())
        .defaultValue(AbstractAWSProcessor.createAllowableValue(Regions.DEFAULT_REGION).getValue())
        .build();

    public static final PropertyDescriptor RESOURCE_NAME = new PropertyDescriptor.Builder()
        .name("Amazon Gateway Api ResourceName")
        .description("The Name of the Gateway API Resource")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor PROP_ATTRIBUTES_TO_SEND = new PropertyDescriptor.Builder()
        .name("Attributes to Send")
        .description("Regular expression that defines which attributes to send as HTTP headers in the request. "
                         + "If not defined, no attributes are sent as headers. Also any dynamic properties set will be sent as headers. "
                         + "The dynamic property key will be the header key and the dynamic property value will be interpreted as expression "
                         + "language will be the header value.")
        .required(false)
        .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
        .build();

    public static final PropertyDescriptor PROP_OUTPUT_RESPONSE_REGARDLESS = new PropertyDescriptor.Builder()
        .name("Always Output Response")
        .description("Will force a response FlowFile to be generated and routed to the 'Response' relationship regardless of what the server status code received is "
                         + "or if the processor is configured to put the server response body in the request attribute. In the later configuration a request FlowFile with the "
                         + "response body in the attribute and a typical response FlowFile will be emitted to their respective relationships.")
        .required(false)
        .defaultValue("false")
        .allowableValues("true", "false")
        .build();

    public static final PropertyDescriptor PROP_PENALIZE_NO_RETRY = new PropertyDescriptor.Builder()
        .name("Penalize on \"No Retry\"")
        .description("Enabling this property will penalize FlowFiles that are routed to the \"No Retry\" relationship.")
        .required(false)
        .defaultValue("false")
        .allowableValues("true", "false")
        .build();

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
            .required(false)
            .name(propertyDescriptorName)
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(
                AttributeExpression.ResultType.STRING, true))
            .dynamic(true)
            .expressionLanguageSupported(true)
            .build();
    }


    @Override
    public void onPropertyModified(final PropertyDescriptor descriptor, final String oldValue, final String newValue) {
        if (descriptor.isDynamic()) {
            final Set<String> newDynamicPropertyNames = new HashSet<>(dynamicPropertyNames);
            if (newValue == null) {
                newDynamicPropertyNames.remove(descriptor.getName());
            } else if (oldValue == null) {    // new property
                newDynamicPropertyNames.add(descriptor.getName());
            }
            this.dynamicPropertyNames = Collections.unmodifiableSet(newDynamicPropertyNames);
        } else {
            // compile the attributes-to-send filter pattern
            if (PROP_ATTRIBUTES_TO_SEND.getName().equalsIgnoreCase(descriptor.getName())) {
                if (newValue == null || newValue.isEmpty()) {
                    regexAttributesToSend = null;
                } else {
                    final String trimmedValue = StringUtils.trimToEmpty(newValue);
                    regexAttributesToSend = Pattern.compile(trimmedValue);
                }
            }
        }
    }

    @Override
    protected GenericApiGatewayClient createClient(ProcessContext context, AWSCredentialsProvider awsCredentialsProvider, ClientConfiguration clientConfiguration) {

        GenericApiGatewayClientBuilder builder =  new GenericApiGatewayClientBuilder()
                .withCredentials(awsCredentialsProvider)
                .withClientConfiguration(clientConfiguration)
                .withEndpoint(context.getProperty(AWS_GATEWAY_API_ENDPOINT).getValue())
                .withRegion(Region.getRegion(Regions.fromName(context.getProperty(AWS_GATEWAY_API_REGION).getValue())));
        if (context.getProperty(AWS_API_KEY).isSet()) {
            builder = builder.withApiKey(context.getProperty(AWS_API_KEY).getValue());
        }
        if (providedClient != null) {
            builder = builder.withHttpClient(providedClient);
        }
        return builder.build();
    }

    @Override
    @Deprecated
    protected GenericApiGatewayClient createClient(final ProcessContext context, final AWSCredentials credentials, final ClientConfiguration clientConfiguration) {
        return createClient(context, new AWSStaticCredentialsProvider(credentials), clientConfiguration);
    }

    protected GenericApiGatewayRequestBuilder setHeaderProperties(final ProcessContext context, GenericApiGatewayRequestBuilder requestBuilder, final FlowFile requestFlowFile) {

        Map<String,String> headers = new HashMap<>();
        for (String headerKey : dynamicPropertyNames) {
            String headerValue = context.getProperty(headerKey).evaluateAttributeExpressions(requestFlowFile).getValue();
            headers.put(headerKey,headerValue);
        }

        // iterate through the flowfile attributes, adding any attribute that
        // matches the attributes-to-send pattern. if the pattern is not set
        // (it's an optional property), ignore that attribute entirely
        if (regexAttributesToSend != null && requestFlowFile != null) {
            Map<String, String> attributes = requestFlowFile.getAttributes();
            Matcher m = regexAttributesToSend.matcher("");
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String headerKey = trimToEmpty(entry.getKey());

                // don't include any of the ignored attributes
                if (IGNORED_ATTRIBUTES.contains(headerKey)) {
                    continue;
                }

                // check if our attribute key matches the pattern
                // if so, include in the request as a header
                m.reset(headerKey);
                if (m.matches()) {
                    String headerVal = trimToEmpty(entry.getValue());
                    headers.put(headerKey, headerVal);
                }
            }
        }

        if (!headers.isEmpty()) {
            requestBuilder = requestBuilder.withHeaders(headers);
        }

        return requestBuilder;
    }

    /**
     * Returns a Map of flowfile attributes from the response http headers. Multivalue headers are naively converted to comma separated strings.
     */
    protected Map<String, String> convertAttributesFromHeaders(GenericApiGatewayResponse responseHttp){
        // create a new hashmap to store the values from the connection
        Map<String, String> map = new HashMap<>();
        responseHttp.getHttpResponse().getHeaders().entrySet().forEach( (entry) -> {

            String key = entry.getKey();
            String value = entry.getValue();

            if (key == null) {
                return;
            }

            // we ignore any headers with no actual values (rare)
            if (StringUtils.isBlank(value)) {
                return;
            }

            // put the csv into the map
            map.put(key, value);
        });

        return map;
    }


    private Relationship getRelationShipForName(String name, Set<Relationship> relationships) {
        for (Relationship relationship : relationships) {
            if(relationship.getName().equals(name)) {
                return relationship;
            }
        }
        throw new IllegalStateException("Unknown relationship " + name);
    }

    protected void route(FlowFile request, FlowFile response, ProcessSession session, ProcessContext context, int statusCode, Set<Relationship> relationships){
        // check if we should yield the processor
        if (!isSuccess(statusCode) && request == null) {
            context.yield();
        }

        // If the property to output the response flowfile regardless of status code is set then transfer it
        boolean responseSent = false;
        if (context.getProperty(PROP_OUTPUT_RESPONSE_REGARDLESS).asBoolean()) {
            session.transfer(response, getRelationShipForName(REL_RESPONSE_NAME, relationships));
            responseSent = true;
        }

        // transfer to the correct relationship
        // 2xx -> SUCCESS
        if (isSuccess(statusCode)) {
            // we have two flowfiles to transfer
            if (request != null) {
                session.transfer(request, getRelationShipForName(REL_SUCCESS_REQ_NAME, relationships));
            }
            if (response != null && !responseSent) {
                session.transfer(response, getRelationShipForName(REL_RESPONSE_NAME, relationships));
            }

            // 5xx -> RETRY
        } else if (statusCode / 100 == 5) {
            if (request != null) {
                request = session.penalize(request);
                session.transfer(request, getRelationShipForName(REL_RETRY_NAME, relationships));
            }

            // 1xx, 3xx, 4xx -> NO RETRY
        } else {
            if (request != null) {
                if (context.getProperty(PROP_PENALIZE_NO_RETRY).asBoolean()) {
                    request = session.penalize(request);
                }
                session.transfer(request, getRelationShipForName(REL_NO_RETRY_NAME, relationships));
            }
        }

    }

    protected boolean isSuccess(int statusCode) {
        return statusCode / 100 == 2;
    }

    protected void logRequest(ComponentLog logger, GenericApiGatewayRequest request) {
        logger.debug("\nRequest to remote service:\n\t{}{}\n{}",
                     new Object[]{request.getHttpMethod(), request.getResourcePath(), getLogString(request.getHeaders())});
    }

    protected void logResponse(ComponentLog logger, URL url, GenericApiGatewayResponse response) {
        logger.debug("\nResponse from remote service:\n\t{}\n{}",
                     new Object[]{url.toExternalForm(), getLogString(response.getHttpResponse().getHeaders())});
    }

    private String getLogString(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String value = entry.getValue();
            sb.append("\t");
            sb.append(entry.getKey());
            sb.append(": ");
            sb.append(value);
            sb.append("\n");
        }
        return sb.toString();
    }
}
