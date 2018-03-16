package org.apache.nifi.processors.aws.wag;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.times;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.http.AmazonHttpClient;
import com.amazonaws.http.apache.client.impl.SdkHttpClient;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpContext;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processors.aws.AbstractAWSCredentialsProviderProcessor;
import org.apache.nifi.processors.aws.AbstractAWSProcessor;
import org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class GetAWSGatewayApiTest {
    private TestRunner runner = null;
    private GetAWSGatewayApi mockGetApi = null;
    private SdkHttpClient mockSdkClient = null;

    @Before
    public void setUp() throws Exception{
        mockSdkClient = Mockito.mock(SdkHttpClient.class);
        ClientConfiguration clientConfig = new ClientConfiguration();

        mockGetApi = new GetAWSGatewayApi(new AmazonHttpClient(clientConfig,mockSdkClient,null));
         runner = TestRunners.newTestRunner(mockGetApi);
        runner.setValidateExpressionUsage(false);

        final AWSCredentialsProviderControllerService serviceImpl = new AWSCredentialsProviderControllerService();
        runner.addControllerService("awsCredentialsProvider", serviceImpl);
        runner.setProperty(serviceImpl, AbstractAWSProcessor.ACCESS_KEY, "awsAccessKey");
        runner.setProperty(serviceImpl, AbstractAWSProcessor.SECRET_KEY, "awsSecretKey");
        runner.enableControllerService(serviceImpl);

        runner.setProperty(AbstractAWSCredentialsProviderProcessor.AWS_CREDENTIALS_PROVIDER_SERVICE,
                           "awsCredentialsProvider");
        runner.setProperty(AbstractAWSGatewayApiProcessor.AWS_GATEWAY_API_REGION,"us-east-1");
        runner.setProperty(AbstractAWSGatewayApiProcessor.AWS_API_KEY,"abcd");
        runner.setProperty(AbstractAWSGatewayApiProcessor.RESOURCE_NAME,"/TEST");
        runner.setProperty(AbstractAWSGatewayApiProcessor.AWS_GATEWAY_API_ENDPOINT,
                           "https://foobar.execute-api.us-east-1.amazonaws.com");
    }

    @Test
    public void testGetApiSimple() throws Exception {

        HttpResponse resp = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
        BasicHttpEntity entity = new BasicHttpEntity();
        entity.setContent(new ByteArrayInputStream("test payload".getBytes()));
        resp.setEntity(entity);
        Mockito.doReturn(resp).when(mockSdkClient).execute(any(HttpUriRequest.class), any(HttpContext.class));

        // execute
        runner.assertValid();
        runner.run(1);

        // check
        Mockito.verify(mockSdkClient, times(1)).execute(argThat(new RequestMatcher<HttpUriRequest>(
                                                            x -> {
                                                                return x.getMethod().equals("GET")
                                                                    && x.getFirstHeader("x-api-key").getValue().equals("abcd")
                                                                    && x.getFirstHeader("Authorization").getValue().startsWith("AWS4")
                                                                    && x.getURI().toString().equals("https://foobar.execute-api.us-east-1.amazonaws.com/TEST");
                                                            })),
                                                        any(HttpContext.class));

        runner.assertTransferCount(GetAWSGatewayApi.REL_SUCCESS_REQ, 0);
        runner.assertTransferCount(GetAWSGatewayApi.REL_RESPONSE, 1);
        runner.assertTransferCount(GetAWSGatewayApi.REL_RETRY, 0);
        runner.assertTransferCount(GetAWSGatewayApi.REL_NO_RETRY, 0);
        runner.assertTransferCount(GetAWSGatewayApi.REL_FAILURE, 0);

        final List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(GetAWSGatewayApi.REL_RESPONSE);
        final MockFlowFile ff0 = flowFiles.get(0);

        ff0.assertAttributeEquals(AbstractAWSGatewayApiProcessor.STATUS_CODE, "200");
        ff0.assertContentEquals("test payload");
        ff0.assertAttributeExists(AbstractAWSGatewayApiProcessor.TRANSACTION_ID);
        ff0.assertAttributeEquals(AbstractAWSGatewayApiProcessor.RESOURCE_NAME_ATTR,"/TEST");
    }

    @Test
    public void testSendAttributes() throws Exception {

        HttpResponse resp = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
        BasicHttpEntity entity = new BasicHttpEntity();
        entity.setContent(new ByteArrayInputStream("test payload".getBytes()));
        resp.setEntity(entity);
        Mockito.doReturn(resp).when(mockSdkClient).execute(any(HttpUriRequest.class), any(HttpContext.class));

        // add dynamic property
        runner.setProperty("dynamicHeader","yes!");
        // set the regex
        runner.setProperty(AbstractAWSGatewayApiProcessor.PROP_ATTRIBUTES_TO_SEND, "F.*");

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/plain-text");
        attributes.put("Foo", "Bar");
        runner.enqueue("Hello".getBytes("UTF-8"), attributes);
        // execute
        runner.assertValid();
        runner.run(1);

        Mockito.verify(mockSdkClient, times(1)).execute(argThat(new RequestMatcher<HttpUriRequest>(
                                                            x -> {
                                                                return x.getMethod().equals("GET")
                                                                && x.getFirstHeader("x-api-key").getValue().equals("abcd")
                                                                && x.getFirstHeader("Authorization").getValue().startsWith("AWS4")
                                                                && x.getFirstHeader("dynamicHeader").getValue().equals("yes!")
                                                                && x.getFirstHeader("Foo").getValue().equals("Bar")
                                                                && x.getURI().toString().equals("https://foobar.execute-api.us-east-1.amazonaws.com/TEST");})),
                                                        any(HttpContext.class));
        // check
        runner.assertTransferCount(GetAWSGatewayApi.REL_SUCCESS_REQ, 1);
        runner.assertTransferCount(GetAWSGatewayApi.REL_RESPONSE, 1);
        runner.assertTransferCount(GetAWSGatewayApi.REL_RETRY, 0);
        runner.assertTransferCount(GetAWSGatewayApi.REL_NO_RETRY, 0);
        runner.assertTransferCount(GetAWSGatewayApi.REL_FAILURE, 0);

        final List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(GetAWSGatewayApi.REL_RESPONSE);
        final MockFlowFile ff0 = flowFiles.get(0);

        ff0.assertAttributeEquals(AbstractAWSGatewayApiProcessor.STATUS_CODE, "200");
        ff0.assertContentEquals("test payload");
        ff0.assertAttributeExists(AbstractAWSGatewayApiProcessor.TRANSACTION_ID);
        ff0.assertAttributeEquals(AbstractAWSGatewayApiProcessor.RESOURCE_NAME_ATTR,"/TEST");
    }
}