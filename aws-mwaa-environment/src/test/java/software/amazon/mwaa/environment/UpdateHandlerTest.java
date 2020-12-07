// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.

package software.amazon.mwaa.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.mwaa.model.Environment;
import software.amazon.awssdk.services.mwaa.model.EnvironmentStatus;
import software.amazon.awssdk.services.mwaa.model.GetEnvironmentRequest;
import software.amazon.awssdk.services.mwaa.model.GetEnvironmentResponse;
import software.amazon.awssdk.services.mwaa.model.ResourceNotFoundException;
import software.amazon.awssdk.services.mwaa.model.TagResourceRequest;
import software.amazon.awssdk.services.mwaa.model.UntagResourceRequest;
import software.amazon.awssdk.services.mwaa.model.UpdateEnvironmentRequest;
import software.amazon.awssdk.services.mwaa.model.UpdateEnvironmentResponse;
import software.amazon.awssdk.services.mwaa.model.ValidationException;
import software.amazon.cloudformation.exceptions.CfnInvalidRequestException;
import software.amazon.cloudformation.exceptions.CfnNotFoundException;
import software.amazon.cloudformation.exceptions.CfnNotUpdatableException;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

/**
 * Tests for {@link UpdateHandler}.
 */
@ExtendWith(MockitoExtension.class)
public class UpdateHandlerTest extends HandlerTestBase {
    private static final Integer UPDATED_MAX_WORKERS = 5;
    private static final String NEW_TAG_KEY = "NEW_KEY";
    private static final String NEW_TAG_VALUE = "NEW_VALUE";
    private static final String INVALID_DATA = "INVALID_DATA";

    /**
     * Prepares mocks.
     */
    @BeforeEach
    public void setup() {
        setupProxies();
    }

    /**
     * Makes sure SDK client is called and not overused.
     */
    @AfterEach
    public void tearDown() {
        verify(getSdkClient(), atLeastOnce()).serviceName();
        verifyNoMoreInteractions(getSdkClient());
    }

    /**
     * Tests a happy path.
     */
    @Test
    public void handleRequestSimpleSuccess() {
        // given
        final UpdateHandler handler = new UpdateHandler();
        final ResourceModel model = createCfnModel();
        model.setMaxWorkers(UPDATED_MAX_WORKERS);
        model.setTags(ImmutableMap.of(NEW_TAG_KEY, NEW_TAG_VALUE));
        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final GetEnvironmentResponse existing = createGetExistingEnvironmentResponse();
        final GetEnvironmentResponse updating = createGetUpdatingEnvironmentResponse();
        final GetEnvironmentResponse updated = createGetUpdatedEnvironmentResponse();

        when(getSdkClient().getEnvironment(any(GetEnvironmentRequest.class)))
                // at first the environment exists
                .thenReturn(existing)
                // then it stays in updating mode for a while
                .thenReturn(updating)
                // at the end it is updated
                .thenReturn(updated);

        final UpdateEnvironmentResponse awsUpdateEnvironmentResponse = UpdateEnvironmentResponse.builder().build();
        when(getSdkClient().updateEnvironment(any(UpdateEnvironmentRequest.class)))
                .thenReturn(awsUpdateEnvironmentResponse);

        // when
        ProgressEvent<ResourceModel, CallbackContext> response = handler.handleRequest(
                getProxies(), request, new CallbackContext());

        // then
        checkResponseNeedsCallback(response);
        // when called back
        response = handler.handleRequest(getProxies(), request, response.getCallbackContext());

        // then
        checkResponseNeedsCallback(response);

        // when called back after environment is updated
        response = handler.handleRequest(getProxies(), request, response.getCallbackContext());

        // then
        checkResponseIsSuccess(response, request.getDesiredResourceState());
        verify(getSdkClient(), times(1)).untagResource(any(UntagResourceRequest.class));
        verify(getSdkClient(), times(1)).tagResource(any(TagResourceRequest.class));
    }

    /**
     * Tests a sad path.
     */
    @Test
    public void handleResourceMissingDuringUpdate() {
        // given
        final UpdateHandler handler = new UpdateHandler();
        final ResourceModel model = createCfnModel();
        model.setMaxWorkers(UPDATED_MAX_WORKERS);
        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final GetEnvironmentResponse existing = createGetExistingEnvironmentResponse();
        final GetEnvironmentResponse updating = createGetUpdatingEnvironmentResponse();

        when(getSdkClient().getEnvironment(any(GetEnvironmentRequest.class)))
                // at first the environment exists
                .thenReturn(existing)
                // then it stays in updating mode for a while
                .thenReturn(updating)
                // then it is gone
                .thenThrow(ResourceNotFoundException.class);

        final UpdateEnvironmentResponse awsUpdateEnvironmentResponse = UpdateEnvironmentResponse.builder().build();
        when(getSdkClient().updateEnvironment(any(UpdateEnvironmentRequest.class)))
                .thenReturn(awsUpdateEnvironmentResponse);

        // when
        ProgressEvent<ResourceModel, CallbackContext> response = handler.handleRequest(
                getProxies(), request, new CallbackContext());

        // then
        checkResponseNeedsCallback(response);

        // when called back
        response = handler.handleRequest(getProxies(), request, response.getCallbackContext());

        // then
        checkResponseNeedsCallback(response);

        // when called back after environment is lost
        response = handler.handleRequest(getProxies(), request, response.getCallbackContext());

        // then
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getCallbackDelaySeconds()).isEqualTo(0);
        assertThat(response.getResourceModel()).isEqualTo(request.getDesiredResourceState());
        assertThat(response.getResourceModels()).isNull();
        assertThat(response.getMessage()).isEqualTo("Update failed, resource no longer exists");
        assertThat(response.getErrorCode()).isEqualTo(HandlerErrorCode.NotStabilized);
        assertThat(response.getCallbackContext()).isNull();
    }

    /**
     * Asserts throwing {@link CfnNotFoundException} when the environment to update does not exist.
     */
    @Test
    public void handleRequestNonExistenceEnvironment() {
        // given
        final UpdateHandler handler = new UpdateHandler();
        final ResourceModel model = ResourceModel.builder().name("NAME").build();
        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        when(getSdkClient().getEnvironment(any(GetEnvironmentRequest.class)))
                .thenThrow(ResourceNotFoundException.class);

        // when
        try {
            handler.handleRequest(
                    getProxies(),
                    request,
                    new CallbackContext());
            // then
            fail("Expected CfnNotFoundException");
        } catch (CfnNotFoundException e) {
            // expect exception
            assertThat(e.getMessage().contains(ResourceModel.TYPE_NAME)).isTrue();
        }
    }

    /**
     * Asserts throwing {@link CfnInvalidRequestException} when given model has invalid data.
     */
    @Test
    public void handleRequestInvalidInput() {
        // given
        final UpdateHandler handler = new UpdateHandler();
        final ResourceModel model = ResourceModel.builder().name("NAME").kmsKey(INVALID_DATA).build();
        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final GetEnvironmentResponse awsGetExistingEnvironmentResponse = createGetExistingEnvironmentResponse();

        when(getSdkClient().getEnvironment(any(GetEnvironmentRequest.class)))
                .thenReturn(awsGetExistingEnvironmentResponse);

        when(getSdkClient().updateEnvironment(any(UpdateEnvironmentRequest.class)))
                .thenThrow(ValidationException.builder().message(INVALID_DATA).build());

        // when
        try {
            handler.handleRequest(
                    getProxies(),
                    request,
                    new CallbackContext());
            // then
            fail("Expected CfnInvalidRequestException");
        } catch (CfnInvalidRequestException e) {
            // expect exception
            assertThat(e.getMessage().contains(INVALID_DATA)).isTrue();
        }

        verify(getSdkClient(), atLeastOnce()).untagResource(any(UntagResourceRequest.class));
    }

    /**
     * Simulates a deleted environment during update and assert it should throw {@link CfnNotUpdatableException}.
     */
    @Test
    public void handleRequestNonUpdatableEnvironment() {
        // given
        final UpdateHandler handler = new UpdateHandler();
        final ResourceModel model = ResourceModel.builder().build();
        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final GetEnvironmentResponse awsGetExistingEnvironmentResponse = createGetExistingEnvironmentResponse();

        when(getSdkClient().getEnvironment(any(GetEnvironmentRequest.class)))
                .thenReturn(awsGetExistingEnvironmentResponse);

        when(getSdkClient().updateEnvironment(any(UpdateEnvironmentRequest.class)))
                .thenThrow(ResourceNotFoundException.class);

        // when
        try {
            handler.handleRequest(
                    getProxies(),
                    request,
                    new CallbackContext());
            // then
            fail("Expected CfnNotUpdatableException");
        } catch (CfnNotUpdatableException e) {
            // expect exception
            assertThat(e.getMessage().contains(ResourceModel.TYPE_NAME)).isTrue();
        }

        verify(getSdkClient(), atLeastOnce()).untagResource(any(UntagResourceRequest.class));
    }

    private GetEnvironmentResponse createGetExistingEnvironmentResponse() {
        final Environment environment = createApiEnvironment(EnvironmentStatus.AVAILABLE);
        return GetEnvironmentResponse.builder().environment(environment).build();
    }

    private GetEnvironmentResponse createGetUpdatingEnvironmentResponse() {
        final Environment environment = createApiEnvironment(EnvironmentStatus.UPDATING);
        return GetEnvironmentResponse.builder().environment(environment).build();
    }

    private GetEnvironmentResponse createGetUpdatedEnvironmentResponse() {
        final Environment environment = createApiEnvironment(EnvironmentStatus.AVAILABLE)
                .toBuilder()
                .maxWorkers(UPDATED_MAX_WORKERS)
                .tags(ImmutableMap.of(NEW_TAG_KEY, NEW_TAG_VALUE))
                .build();
        return GetEnvironmentResponse.builder().environment(environment).build();
    }
}