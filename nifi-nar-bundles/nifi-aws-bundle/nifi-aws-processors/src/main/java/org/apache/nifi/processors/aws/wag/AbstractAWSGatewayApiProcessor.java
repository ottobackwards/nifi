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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.aws.AbstractAWSCredentialsProviderProcessor;
import org.apache.nifi.processors.aws.AbstractAWSProcessor;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayClient;
import org.apache.nifi.processors.aws.wag.client.GenericApiGatewayClientBuilder;

/**
 * This class is the base class for invoking aws gateway api endpoints
 */
public abstract class AbstractAWSGatewayApiProcessor extends AbstractAWSCredentialsProviderProcessor<GenericApiGatewayClient> {
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
        return builder.build();
    }

    @Override
    @Deprecated
    protected GenericApiGatewayClient createClient(final ProcessContext context, final AWSCredentials credentials, final ClientConfiguration clientConfiguration) {
        return createClient(context, new AWSStaticCredentialsProvider(credentials), clientConfiguration);
    }
}
