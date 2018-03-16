package org.apache.nifi.processors.aws.wag;

import com.amazonaws.http.AmazonHttpClient;
import com.amazonaws.http.HttpMethodName;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
@InputRequirement(Requirement.INPUT_FORBIDDEN)
@Tags({"Amazon","AWS","Get","Gateway-API"})
@CapabilityDescription("Retrieves the result of an http GET for an AWS Gateway API endpoint resource")
@WritesAttributes({
        @WritesAttribute(attribute = "mime.type", description = "The MIME Type of the flowfiles"),
        @WritesAttribute(attribute = "aws.gateway.api.status.code", description = "The status code that is returned"),
        @WritesAttribute(attribute = "aws.gateway.api.status.message", description = "The status message that is returned"),
        @WritesAttribute(attribute = "aws.gateway.api.resource", description = "The request resource"),
        @WritesAttribute(attribute = "aws.gateway.api.tx.id", description = "The transaction ID that is returned after reading the response"),

})
@DynamicProperty(name = "Header Name", value = "Attribute Expression Language", supportsExpressionLanguage = true, description = "Send request header "
    + "with a key matching the Dynamic Property Key and a value created by evaluating the Attribute Expression Language set in the value "
    + "of the Dynamic Property.")
public class GetAWSGatewayApi extends AbstractAWSGatewayApiProcessor {

    public static final List<PropertyDescriptor> properties = Collections.unmodifiableList(
            Arrays.asList(AWS_GATEWAY_API_REGION, ACCESS_KEY, SECRET_KEY, CREDENTIALS_FILE, AWS_CREDENTIALS_PROVIDER_SERVICE, TIMEOUT,
                    RESOURCE_NAME,AWS_GATEWAY_API_ENDPOINT, AWS_API_KEY, PROP_ATTRIBUTES_TO_SEND));



    public GetAWSGatewayApi() {}
    public GetAWSGatewayApi(AmazonHttpClient client) {
        super(client);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {return properties;}



    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        ComponentLog logger = getLogger();
        FlowFile flowFile = session.create();

        // Every request/response cycle has a unique transaction id which will be stored as a flowfile attribute.
        final UUID txId = UUID.randomUUID();
        final String resourceName = context.getProperty(RESOURCE_NAME).getValue();

        final GenericApiGatewayClient client = getClient();

        final GenericApiGatewayRequestBuilder builder = setHeaderProperties(context, new GenericApiGatewayRequestBuilder().withResourcePath(resourceName)
                                                                                            .withHttpMethod(HttpMethodName.GET), flowFile);


        final GenericApiGatewayResponse response = client.execute(builder.build());

        final int statusCode = response.getHttpResponse().getStatusCode();
        final String statusExplanation = response.getHttpResponse().getStatusText();

        // Create a map of the status attributes that are always written to the request and response FlowFiles
        final Map<String, String> statusAttributes = new HashMap<>();
        statusAttributes.put(STATUS_CODE, String.valueOf(statusCode));
        statusAttributes.put(STATUS_MESSAGE, statusExplanation);
        statusAttributes.put(RESOURCE_NAME_ATTR, resourceName);
        statusAttributes.put(TRANSACTION_ID, txId.toString());

        if (flowFile != null) {
            flowFile = session.putAllAttributes(flowFile, statusAttributes);
        }

        if ((statusCode >= 300) || (statusCode == 204)) {
            logger.error("received status code {}:{} from {}", new Object[]{statusCode,statusExplanation,resourceName});
            session.commit();
            return;
        }

        flowFile = session.putAllAttributes(flowFile, convertAttributesFromHeaders(response));

        final String contentType = response.getHttpResponse().getHeaders().get("Content-Type");
        if (!(contentType == null) && !contentType.trim().isEmpty()) {
            flowFile = session.putAttribute(flowFile, CoreAttributes.MIME_TYPE.key(), contentType.trim());
        }

        flowFile = session.importFrom(new ByteArrayInputStream(response.getBody().getBytes()),flowFile);
        session.transfer(flowFile, REL_SUCCESS);

    }
}
