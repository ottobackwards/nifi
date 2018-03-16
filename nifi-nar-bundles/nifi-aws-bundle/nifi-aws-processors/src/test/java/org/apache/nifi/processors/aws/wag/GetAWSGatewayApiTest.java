package org.apache.nifi.processors.aws.wag;

import static org.mockito.Matchers.any;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.http.AmazonHttpClient;
import com.amazonaws.http.apache.client.impl.SdkHttpClient;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpContext;
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

    }

    @Test
    public void testGetApiSimple() throws Exception {

        final AWSCredentialsProviderControllerService serviceImpl = new AWSCredentialsProviderControllerService();
        runner.addControllerService("awsCredentialsProvider", serviceImpl);
        runner.setProperty(serviceImpl, AbstractAWSProcessor.ACCESS_KEY, "awsAccessKey");
        runner.setProperty(serviceImpl, AbstractAWSProcessor.SECRET_KEY, "awsSecretKey");
        runner.enableControllerService(serviceImpl);

        // set the properties
        runner.setProperty(AbstractAWSCredentialsProviderProcessor.AWS_CREDENTIALS_PROVIDER_SERVICE,
                           "awsCredentialsProvider");
        runner.setProperty(AbstractAWSGatewayApiProcessor.AWS_GATEWAY_API_REGION,"us-east-1");
        runner.setProperty(AbstractAWSGatewayApiProcessor.RESOURCE_NAME,"/TEST");
        runner.setProperty(AbstractAWSGatewayApiProcessor.AWS_API_KEY,"abcd");
        runner.setProperty(AbstractAWSGatewayApiProcessor.AWS_GATEWAY_API_ENDPOINT,
                           "https://foobar.execute-api.us-east-1.amazonaws.com");



        HttpResponse resp = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
        BasicHttpEntity entity = new BasicHttpEntity();
        entity.setContent(new ByteArrayInputStream("test payload".getBytes()));
        resp.setEntity(entity);
        Mockito.doReturn(resp).when(mockSdkClient).execute(any(HttpUriRequest.class), any(HttpContext.class));




        // execute
        runner.assertValid();
        runner.run(1);

        // check
        runner.assertAllFlowFilesTransferred(GetAWSGatewayApi.REL_SUCCESS,1);
        final List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(GetAWSGatewayApi.REL_SUCCESS);
        final MockFlowFile ff0 = flowFiles.get(0);
        ff0.assertAttributeEquals(AbstractAWSGatewayApiProcessor.STATUS_CODE, "200");

    }
}