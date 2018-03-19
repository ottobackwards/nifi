package org.apache.nifi.processors.aws.wag;

import com.amazonaws.http.AmazonHttpClient;
import com.amazonaws.http.HttpMethodName;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.TriggerWhenEmpty;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayClient;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayRequestBuilder;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayResponse;

@TriggerWhenEmpty
@InputRequirement(Requirement.INPUT_ALLOWED)
@Tags({"Amazon","AWS","Get","Gateway-API"})
@CapabilityDescription("Retrieves the result of an http GET for an AWS Gateway API endpoint resource")
@WritesAttributes({
        @WritesAttribute(attribute = "mime.type", description = "The MIME Type of the flowfiles"),
        @WritesAttribute(attribute = "aws.gateway.api.status.code", description = "The status code that is returned"),
        @WritesAttribute(attribute = "aws.gateway.api.status.message", description = "The status message that is returned"),
        @WritesAttribute(attribute = "aws.gateway.api.resource", description = "The request resource"),
        @WritesAttribute(attribute = "aws.gateway.api.tx.id", description = "The transaction ID that is returned after reading the response"),
        @WritesAttribute(attribute = "aws.gateway.api.java.exception.class", description = "The Java exception class raised when the processor fails"),
        @WritesAttribute(attribute = "aws.gateway.api.java.exception.message", description = "The Java exception message raised when the processor fails"),
})
@DynamicProperty(name = "Header Name", value = "Attribute Expression Language", supportsExpressionLanguage = true, description = "Send request header "
    + "with a key matching the Dynamic Property Key and a value created by evaluating the Attribute Expression Language set in the value "
    + "of the Dynamic Property.")
public class GetAWSGatewayApi extends AbstractAWSGatewayApiProcessor {

    public static final List<PropertyDescriptor> properties = Collections.unmodifiableList(
            Arrays.asList(AWS_GATEWAY_API_REGION, ACCESS_KEY, SECRET_KEY, CREDENTIALS_FILE,
                          AWS_CREDENTIALS_PROVIDER_SERVICE, TIMEOUT, RESOURCE_NAME,
                          AWS_GATEWAY_API_ENDPOINT, AWS_API_KEY, PROP_ATTRIBUTES_TO_SEND,
                          PROP_OUTPUT_RESPONSE_REGARDLESS, PROP_PENALIZE_NO_RETRY,
                          PROXY_HOST,PROXY_HOST_PORT,PROP_PROXY_USER,PROP_PROXY_PASSWORD));


    public static final Relationship REL_SUCCESS_REQ = new Relationship.Builder()
        .name(REL_SUCCESS_REQ_NAME)
        .description("The original FlowFile will be routed upon success (2xx status codes). It will have new attributes detailing the "
                         + "success of the request.")
        .build();

    public static final Relationship REL_RESPONSE = new Relationship.Builder()
        .name(REL_RESPONSE_NAME)
        .description("A Response FlowFile will be routed upon success (2xx status codes). If the 'Output Response Regardless' property "
                         + "is true then the response will be sent to this relationship regardless of the status code received.")
        .build();

    public static final Relationship REL_RETRY = new Relationship.Builder()
        .name(REL_RETRY_NAME)
        .description("The original FlowFile will be routed on any status code that can be retried (5xx status codes). It will have new "
                         + "attributes detailing the request.")
        .build();

    public static final Relationship REL_NO_RETRY = new Relationship.Builder()
        .name(REL_NO_RETRY_NAME)
        .description("The original FlowFile will be routed on any status code that should NOT be retried (1xx, 3xx, 4xx status codes).  "
                         + "It will have new attributes detailing the request.")
        .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
        .name(REL_FAILURE_NAME)
        .description("The original FlowFile will be routed on any type of connection failure, timeout or general exception. "
                         + "It will have new attributes detailing the request.")
        .build();

    public static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        REL_SUCCESS_REQ, REL_RESPONSE, REL_RETRY, REL_NO_RETRY, REL_FAILURE)));

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    public GetAWSGatewayApi() {}
    public GetAWSGatewayApi(AmazonHttpClient client) {
        super(client);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {return properties;}



    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        ComponentLog logger = getLogger();
        FlowFile requestFlowFile = session.get();


        // Every request/response cycle has a unique transaction id which will be stored as a flowfile attribute.
        final UUID txId = UUID.randomUUID();
        FlowFile responseFlowFile = null;

        try {
            final String resourceName = context.getProperty(RESOURCE_NAME).getValue();

            final GenericApiGatewayClient client = getClient();

            final GenericApiGatewayRequestBuilder builder = setHeaderProperties(context, new GenericApiGatewayRequestBuilder()
                .withResourcePath(resourceName).withHttpMethod(HttpMethodName.GET), requestFlowFile);
            final long startNanos = System.nanoTime();
            final GenericApiGatewayResponse response = client.execute(builder.build());

            final int statusCode = response.getHttpResponse().getStatusCode();

            if (statusCode == 0) {
                throw new IllegalStateException(
                    "Status code unknown, connection hasn't been attempted.");
            }
            boolean outputBodyToResponseContent = (isSuccess(statusCode) || context.getProperty(PROP_OUTPUT_RESPONSE_REGARDLESS).asBoolean());
            boolean bodyExists = response.getBody() != null;

            final String statusExplanation = response.getHttpResponse().getStatusText();

            // Create a map of the status attributes that are always written to the request and response FlowFiles
            final Map<String, String> statusAttributes = new HashMap<>();
            statusAttributes.put(STATUS_CODE, String.valueOf(statusCode));
            statusAttributes.put(STATUS_MESSAGE, statusExplanation);
            statusAttributes.put(ENDPOINT_ATTR, client.getEndpointPrefix());
            statusAttributes.put(RESOURCE_NAME_ATTR, resourceName);
            statusAttributes.put(TRANSACTION_ID, txId.toString());


            if (outputBodyToResponseContent) {
                /*
                 * If successful and putting to response flowfile, store the response body as the flowfile payload
                 * we include additional flowfile attributes including the response headers and the status codes.
                 */

                // clone the flowfile to capture the response
                if (requestFlowFile != null) {
                    responseFlowFile = session.create(requestFlowFile);
                } else {
                    responseFlowFile = session.create();
                }

                // write attributes to response flowfile
                responseFlowFile = session.putAllAttributes(responseFlowFile, statusAttributes);

                // write the response headers as attributes
                // this will overwrite any existing flowfile attributes
                responseFlowFile = session.putAllAttributes(responseFlowFile, convertAttributesFromHeaders(response));

                // transfer the message body to the payload
                // can potentially be null in edge cases
                if (bodyExists) {
                    final String endpoint = context.getProperty(AWS_GATEWAY_API_ENDPOINT).getValue();
                    final String contentType = response.getHttpResponse().getHeaders().get("Content-Type");
                    if (!(contentType == null) && !contentType.trim().isEmpty()) {
                        responseFlowFile = session
                            .putAttribute(responseFlowFile, CoreAttributes.MIME_TYPE.key(), contentType.trim());
                    }

                    responseFlowFile = session.importFrom(new ByteArrayInputStream(response.getBody().getBytes()),
                                                          responseFlowFile);

                    // emit provenance event
                    final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    if(requestFlowFile != null) {
                        session.getProvenanceReporter().fetch(responseFlowFile,endpoint, millis);
                    } else {
                        session.getProvenanceReporter().receive(responseFlowFile,endpoint, millis);
                    }
                }
            }
            route(requestFlowFile, responseFlowFile, session, context, statusCode, getRelationships());
        } catch (Exception e) {
            // penalize or yield
            if (requestFlowFile != null) {
                logger.error("Routing to {} due to exception: {}", new Object[]{REL_FAILURE.getName(), e}, e);
                requestFlowFile = session.penalize(requestFlowFile);
                requestFlowFile = session.putAttribute(requestFlowFile, EXCEPTION_CLASS, e.getClass().getName());
                requestFlowFile = session.putAttribute(requestFlowFile, EXCEPTION_MESSAGE, e.getMessage());
                // transfer original to failure
                session.transfer(requestFlowFile, REL_FAILURE);
            } else {
                logger.error("Yielding processor due to exception encountered as a source processor: {}", e);
                context.yield();
            }


            // cleanup response flowfile, if applicable
            try {
                if (responseFlowFile != null) {
                    session.remove(responseFlowFile);
                }
            } catch (final Exception e1) {
                logger.error("Could not cleanup response flowfile due to exception: {}", new Object[]{e1}, e1);
            }
        }
    }
}
