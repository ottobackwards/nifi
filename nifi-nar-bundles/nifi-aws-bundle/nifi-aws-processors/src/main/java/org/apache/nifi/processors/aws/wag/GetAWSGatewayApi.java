package org.apache.nifi.processors.aws.wag;

import com.amazonaws.http.HttpMethodName;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.TriggerWhenEmpty;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
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
        @WritesAttribute(attribute = "mime.type", description = "The MIME Type of the flowfiles")
})
public class GetAWSGatewayApi extends AbstractAWSGatewayApiProcessor {




    public static final List<PropertyDescriptor> properties = Collections.unmodifiableList(
            Arrays.asList(AWS_GATEWAY_API_REGION, ACCESS_KEY, SECRET_KEY, CREDENTIALS_FILE, AWS_CREDENTIALS_PROVIDER_SERVICE, TIMEOUT,
                    RESOURCE_NAME,AWS_GATEWAY_API_ENDPOINT, AWS_API_KEY));

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {return properties;}

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        ComponentLog logger = getLogger();
        FlowFile flowFile = session.create();

        final String resourceName = context.getProperty(RESOURCE_NAME).getValue();

        final GenericApiGatewayClient client = getClient();

        final GenericApiGatewayResponse response = client.execute(
                new GenericApiGatewayRequestBuilder().withResourcePath(resourceName)
                                                     .withHttpMethod(HttpMethodName.GET).build()
        );

        // write the status code?
        // how do we handle codes?
        //response.getHttpResponse().getStatusCode()
        final int statusCode = response.getHttpResponse().getStatusCode();
        final String statusExplanation = response.getHttpResponse().getStatusText();
        if ((statusCode >= 300) || (statusCode == 204)) {
            logger.error("recieved status code {}:{} from {}", new Object[]{statusCode,statusExplanation,resourceName});
            session.commit();
            return;
        }

        final String contentType = response.getHttpResponse().getHeaders().get("Content-Type");
        if (!(contentType == null) && !contentType.trim().isEmpty()) {
            flowFile = session.putAttribute(flowFile, CoreAttributes.MIME_TYPE.key(), contentType.trim());
        }

        flowFile = session.importFrom(new ByteArrayInputStream(response.getBody().getBytes()),flowFile);
        session.transfer(flowFile, REL_SUCCESS);

    }
}
