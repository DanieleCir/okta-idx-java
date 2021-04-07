/*
 * Copyright 2020-Present Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.idx.sdk.api.wrapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.okta.commons.lang.Assert;
import com.okta.idx.sdk.api.client.IDXClient;
import com.okta.idx.sdk.api.exception.ProcessingException;
import com.okta.idx.sdk.api.model.AuthenticationOptions;
import com.okta.idx.sdk.api.model.AuthenticationStatus;
import com.okta.idx.sdk.api.model.Authenticator;
import com.okta.idx.sdk.api.model.AuthenticatorType;
import com.okta.idx.sdk.api.model.ChangePasswordOptions;
import com.okta.idx.sdk.api.model.Credentials;
import com.okta.idx.sdk.api.model.FormValue;
import com.okta.idx.sdk.api.model.IDXClientContext;
import com.okta.idx.sdk.api.model.RecoverPasswordOptions;
import com.okta.idx.sdk.api.model.RemediationOption;
import com.okta.idx.sdk.api.model.RemediationType;
import com.okta.idx.sdk.api.model.UserProfile;
import com.okta.idx.sdk.api.model.VerifyAuthenticatorOptions;
import com.okta.idx.sdk.api.request.AnswerChallengeRequest;
import com.okta.idx.sdk.api.request.AnswerChallengeRequestBuilder;
import com.okta.idx.sdk.api.request.ChallengeRequest;
import com.okta.idx.sdk.api.request.ChallengeRequestBuilder;
import com.okta.idx.sdk.api.request.EnrollRequest;
import com.okta.idx.sdk.api.request.EnrollRequestBuilder;
import com.okta.idx.sdk.api.request.EnrollUserProfileUpdateRequest;
import com.okta.idx.sdk.api.request.EnrollUserProfileUpdateRequestBuilder;
import com.okta.idx.sdk.api.request.IdentifyRequest;
import com.okta.idx.sdk.api.request.IdentifyRequestBuilder;
import com.okta.idx.sdk.api.request.RecoverRequest;
import com.okta.idx.sdk.api.request.RecoverRequestBuilder;
import com.okta.idx.sdk.api.request.SkipAuthenticatorEnrollmentRequest;
import com.okta.idx.sdk.api.request.SkipAuthenticatorEnrollmentRequestBuilder;
import com.okta.idx.sdk.api.response.AuthenticationResponse;
import com.okta.idx.sdk.api.response.IDXResponse;
import com.okta.idx.sdk.api.response.NewUserRegistrationResponse;
import com.okta.idx.sdk.api.response.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class AuthenticationWrapper {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationWrapper.class);

    /**
     * Authenticate user with the supplied Authentication options (username and password) and
     * returns the Authentication response object that contains:
     * - IDX Client context
     * - Token (access_token/id_token/refresh_token) object
     * - Authentication status
     * <p>
     * Note: This requires 'Password' as the ONLY required factor in app Sign-on policy configuration.
     *
     * @param client                the IDX Client
     * @param authenticationOptions the Authenticator options
     * @return the Authentication response
     */
    public static AuthenticationResponse authenticate(IDXClient client, AuthenticationOptions authenticationOptions) {

        AuthenticationResponse authenticationResponse = new AuthenticationResponse();
        TokenResponse tokenResponse;
        IDXClientContext idxClientContext;

        try {
            idxClientContext = client.interact();
            Assert.notNull(idxClientContext, "IDX client context may not be null");
            authenticationResponse.setIdxClientContext(idxClientContext);

            IDXResponse introspectResponse = client.introspect(idxClientContext);
            String stateHandle = introspectResponse.getStateHandle();
            Assert.hasText(stateHandle, "State handle may not be null");

            RemediationOption[] remediationOptions = introspectResponse.remediation().remediationOptions();
            printRemediationOptions(remediationOptions);

            RemediationOption remediationOption = extractRemediationOption(remediationOptions, RemediationType.IDENTIFY);

            // Check if identify flow needs to include credentials
            boolean isIdentifyInOneStep = isRemediationRequireCredentials(RemediationType.IDENTIFY, introspectResponse);

            IdentifyRequest identifyRequest;

            if (isIdentifyInOneStep) {
                Credentials credentials = new Credentials();
                credentials.setPasscode(authenticationOptions.getPassword().toCharArray());

                identifyRequest = IdentifyRequestBuilder.builder()
                        .withIdentifier(authenticationOptions.getUsername())
                        .withCredentials(credentials)
                        .withStateHandle(stateHandle)
                        .build();
            } else {
                identifyRequest = IdentifyRequestBuilder.builder()
                        .withIdentifier(authenticationOptions.getUsername())
                        .withStateHandle(stateHandle)
                        .build();
            }

            // identify user
            IDXResponse identifyResponse = remediationOption.proceed(client, identifyRequest);

            if (isIdentifyInOneStep) {
                // we expect success
                if (!identifyResponse.isLoginSuccessful()) {
                    // verify if password expired
                    if (isRemediationRequireCredentials(RemediationType.REENROLL_AUTHENTICATOR, identifyResponse)) {
                        logger.warn("Password expired!");
                        authenticationResponse.setAuthenticationStatus(AuthenticationStatus.PASSWORD_EXPIRED);
                    } else {
                        String errMsg = "Unexpected remediation: " + RemediationType.REENROLL_AUTHENTICATOR;
                        logger.error("{}", errMsg);
                        Arrays.stream(identifyResponse.getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
                    }
                } else {
                    // login successful
                    logger.info("Login Successful!");
                    tokenResponse = identifyResponse.getSuccessWithInteractionCode().exchangeCode(client, idxClientContext);
                    authenticationResponse.setAuthenticationStatus(AuthenticationStatus.SUCCESS);
                    authenticationResponse.setTokenResponse(tokenResponse);
                }
            } else {
                if (identifyResponse.getMessages() != null) {
                    Arrays.stream(identifyResponse.getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
                }
                else if (!isRemediationRequireCredentials(RemediationType.CHALLENGE_AUTHENTICATOR, identifyResponse)) {
                    String errMsg = "Unexpected remediation: " + RemediationType.CHALLENGE_AUTHENTICATOR;
                    logger.error("{}", errMsg);
                    Arrays.stream(identifyResponse.getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
                } else {
                    remediationOptions = identifyResponse.remediation().remediationOptions();
                    printRemediationOptions(remediationOptions);

                    remediationOption = extractRemediationOption(remediationOptions, RemediationType.CHALLENGE_AUTHENTICATOR);

                    // answer password authenticator challenge
                    Credentials credentials = new Credentials();
                    credentials.setPasscode(authenticationOptions.getPassword().toCharArray());

                    // build answer password authenticator challenge request
                    AnswerChallengeRequest passwordAuthenticatorAnswerChallengeRequest = AnswerChallengeRequestBuilder.builder()
                            .withStateHandle(stateHandle)
                            .withCredentials(credentials)
                            .build();
                    IDXResponse challengeResponse = remediationOption.proceed(client, passwordAuthenticatorAnswerChallengeRequest);

                    if (!challengeResponse.isLoginSuccessful()) {
                        // verify if password expired
                        if (isRemediationRequireCredentials(RemediationType.REENROLL_AUTHENTICATOR, challengeResponse)) {
                            authenticationResponse.setAuthenticationStatus(AuthenticationStatus.PASSWORD_EXPIRED);
                        } else {
                            String errMsg = "Unexpected remediation: " + RemediationType.REENROLL_AUTHENTICATOR;
                            logger.error("{}", errMsg);
                            Arrays.stream(identifyResponse.getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
                        }
                    } else {
                        // login successful
                        logger.info("Login Successful!");
                        tokenResponse = challengeResponse.getSuccessWithInteractionCode().exchangeCode(client, idxClientContext);
                        authenticationResponse.setAuthenticationStatus(AuthenticationStatus.SUCCESS);
                        authenticationResponse.setTokenResponse(tokenResponse);
                    }
                }
            }
            return authenticationResponse;
        } catch (ProcessingException e) {
            Arrays.stream(e.getErrorResponse().getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
            logger.error("Something went wrong! {}, {}", e, authenticationResponse.getErrors());
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
            authenticationResponse.addError(e.getMessage());
        }

        return authenticationResponse;
    }

    public static AuthenticationResponse changePassword(IDXClient client, IDXClientContext idxClientContext, ChangePasswordOptions changePasswordOptions) {

        AuthenticationResponse authenticationResponse = new AuthenticationResponse();
        authenticationResponse.setIdxClientContext(idxClientContext);
        TokenResponse tokenResponse;

        try {
            // re-enter flow with context
            IDXResponse introspectResponse = client.introspect(idxClientContext);

            // check if flow is password expiration or forgot password
            RemediationOption[] resetAuthenticatorRemediationOptions = introspectResponse.remediation().remediationOptions();
            printRemediationOptions(resetAuthenticatorRemediationOptions);

            RemediationOption resetAuthenticatorRemediationOption =
                    extractRemediationOption(resetAuthenticatorRemediationOptions, RemediationType.RESET_AUTHENTICATOR);

            // set new password
            Credentials credentials = new Credentials();
            credentials.setPasscode(changePasswordOptions.getNewPassword().toCharArray());

            // build answer password authenticator challenge request
            AnswerChallengeRequest passwordAuthenticatorAnswerChallengeRequest = AnswerChallengeRequestBuilder.builder()
                    .withStateHandle(introspectResponse.getStateHandle())
                    .withCredentials(credentials)
                    .build();

            IDXResponse resetPasswordResponse = resetAuthenticatorRemediationOption.proceed(client, passwordAuthenticatorAnswerChallengeRequest);

            if (resetPasswordResponse.isLoginSuccessful()) {
                // login successful
                logger.info("Login Successful!");
                tokenResponse = resetPasswordResponse.getSuccessWithInteractionCode().exchangeCode(client, idxClientContext);
                authenticationResponse.setAuthenticationStatus(AuthenticationStatus.SUCCESS);
                authenticationResponse.setTokenResponse(tokenResponse);
                return authenticationResponse;
            } else {
                String errMsg = "Unexpected remediation: " + RemediationType.SUCCESS_WITH_INTERACTION_CODE;
                logger.error("{}", errMsg);
                Arrays.stream(resetPasswordResponse.getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
            }
        } catch (ProcessingException e) {
            Arrays.stream(e.getErrorResponse().getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
            logger.error("Something went wrong! {}, {}", e, authenticationResponse.getErrors());
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
            authenticationResponse.addError(e.getMessage());
        }

        return authenticationResponse;
    }

    /**
     * Recover Password with the supplied authenticator options.
     *
     * @param client the IDX Client
     * @param recoverPasswordOptions the password recovery options
     * @return the Authentication response
     */
    public static AuthenticationResponse recoverPassword(IDXClient client, RecoverPasswordOptions recoverPasswordOptions) {

        AuthenticationResponse authenticationResponse = new AuthenticationResponse();
        IDXClientContext idxClientContext;

        try {
            idxClientContext = client.interact();
            Assert.notNull(idxClientContext, "IDX client context may not be null");
            authenticationResponse.setIdxClientContext(idxClientContext);

            IDXResponse introspectResponse = client.introspect(idxClientContext);
            String stateHandle = introspectResponse.getStateHandle();
            Assert.hasText(stateHandle, "State handle may not be null");

            RemediationOption[] remediationOptions = introspectResponse.remediation().remediationOptions();
            printRemediationOptions(remediationOptions);

            RemediationOption remediationOption = extractRemediationOption(remediationOptions, RemediationType.IDENTIFY);

            IdentifyRequest identifyRequest = IdentifyRequestBuilder.builder()
                        .withIdentifier(recoverPasswordOptions.getUsername())
                        .withStateHandle(stateHandle)
                        .build();

            // identify user
            IDXResponse identifyResponse = remediationOption.proceed(client, identifyRequest);

            remediationOptions = identifyResponse.remediation().remediationOptions();
            printRemediationOptions(remediationOptions);

            if (identifyResponse.getCurrentAuthenticatorEnrollment() == null ||
                identifyResponse.getCurrentAuthenticatorEnrollment().getValue() == null ||
                identifyResponse.getCurrentAuthenticatorEnrollment().getValue().getRecover() == null) {
                  Arrays.stream(identifyResponse.getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
                } else {

            // recover password
            RecoverRequest recoverRequest = RecoverRequestBuilder.builder()
                    .withStateHandle(identifyResponse.getStateHandle())
                    .build();

            IDXResponse recoverResponse = identifyResponse.getCurrentAuthenticatorEnrollment().getValue().getRecover()
                    .proceed(client, recoverRequest);

            RemediationOption[] recoverResponseRemediationOptions = recoverResponse.remediation().remediationOptions();
            RemediationOption selectAuthenticatorAuthenticateRemediationOption = extractRemediationOption(recoverResponseRemediationOptions, RemediationType.SELECT_AUTHENTICATOR_AUTHENTICATE);

            Map<String, String> authenticatorOptions = selectAuthenticatorAuthenticateRemediationOption.getAuthenticatorOptions();

            Authenticator authenticator = new Authenticator();

            authenticator.setId(authenticatorOptions.get(recoverPasswordOptions.getAuthenticatorType().toString()));

            ChallengeRequest selectAuthenticatorRequest = ChallengeRequestBuilder.builder()
                    .withStateHandle(stateHandle)
                    .withAuthenticator(authenticator)
                    .build();

            IDXResponse selectAuthenticatorResponse = selectAuthenticatorAuthenticateRemediationOption.proceed(client, selectAuthenticatorRequest);

            RemediationOption[] selectAuthenticatorResponseRemediationOptions = selectAuthenticatorResponse.remediation().remediationOptions();

            RemediationOption challengeAuthenticatorRemediationOption =
                    extractRemediationOption(selectAuthenticatorResponseRemediationOptions, RemediationType.CHALLENGE_AUTHENTICATOR);

            authenticationResponse.setAuthenticationStatus(AuthenticationStatus.AWAITING_AUTHENTICATOR_VERIFICATION);
          }
        } catch (ProcessingException e) {
            Arrays.stream(e.getErrorResponse().getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
            logger.error("Something went wrong! {}, {}", e, authenticationResponse.getErrors());
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
            authenticationResponse.addError(e.getMessage());
        }

        return authenticationResponse;
    }

    /**
     * Verify Authenticator with the supplied authenticator options.
     *
     * @param client                the IDX Client
     * @param idxClientContext      the IDX Client context
     * @param verifyAuthenticatorOptions the verify Authenticator options
     * @return the Authentication response
     */
    public static AuthenticationResponse verifyAuthenticator(IDXClient client, IDXClientContext idxClientContext, VerifyAuthenticatorOptions verifyAuthenticatorOptions) {

        AuthenticationResponse authenticationResponse = new AuthenticationResponse();
        authenticationResponse.setIdxClientContext(idxClientContext);

        try {
            // re-enter flow with context
            IDXResponse introspectResponse = client.introspect(idxClientContext);

            // verify if password expired
            if (!isRemediationRequireCredentials(RemediationType.CHALLENGE_AUTHENTICATOR, introspectResponse)) {
                String errMsg = "Unexpected remediation: " + RemediationType.CHALLENGE_AUTHENTICATOR;
                logger.error("{}", errMsg);
            } else {

                Credentials credentials = new Credentials();
                credentials.setPasscode(verifyAuthenticatorOptions.getCode().toCharArray());

                // build answer password authenticator challenge request
                AnswerChallengeRequest challengeAuthenticatorRequest = AnswerChallengeRequestBuilder.builder()
                        .withStateHandle(introspectResponse.getStateHandle())
                        .withCredentials(credentials)
                        .build();

                RemediationOption[] introspectRemediationOptions = introspectResponse.remediation().remediationOptions();
                printRemediationOptions(introspectRemediationOptions);

                RemediationOption challengeAuthenticatorRemediationOption =
                        extractRemediationOption(introspectRemediationOptions, RemediationType.CHALLENGE_AUTHENTICATOR);

                IDXResponse challengeAuthenticatorResponse =
                        challengeAuthenticatorRemediationOption.proceed(client, challengeAuthenticatorRequest);

                RemediationOption[] challengeAuthenticatorResponseRemediationOptions =
                        challengeAuthenticatorResponse.remediation().remediationOptions();
                printRemediationOptions(challengeAuthenticatorResponseRemediationOptions);

                RemediationOption resetAuthenticatorRemediationOption =
                        extractRemediationOption(challengeAuthenticatorResponseRemediationOptions, RemediationType.RESET_AUTHENTICATOR);

                authenticationResponse.setAuthenticationStatus(AuthenticationStatus.AWAITING_PASSWORD_RESET);
            }
        } catch (ProcessingException e) {
          logger.error("Error occurred", e);
            Arrays.stream(e.getErrorResponse().getMessages().getValue()).forEach(msg -> authenticationResponse.addError(msg.getMessage()));
            logger.error("Something went wrong! {}, {}", e, authenticationResponse.getErrors());
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
            authenticationResponse.addError(e.getMessage());
        }

        return authenticationResponse;
    }

    /**
     * Fetch Form Values for signing up a new user.
     *
     * @param client                the IDX Client
     * @return the new user registration response
     */
    public static NewUserRegistrationResponse fetchSignUpFormValues(IDXClient client) {

        List<FormValue> enrollProfileFormValues;

        NewUserRegistrationResponse newUserRegistrationResponse = new NewUserRegistrationResponse();

        try {
            IDXClientContext idxClientContext = client.interact();
            Assert.notNull(idxClientContext, "IDX client context may not be null");

            IDXResponse introspectResponse = client.introspect(idxClientContext);
            String stateHandle = introspectResponse.getStateHandle();
            Assert.hasText(stateHandle, "State handle may not be null");

            RemediationOption[] remediationOptions = introspectResponse.remediation().remediationOptions();
            printRemediationOptions(remediationOptions);

            RemediationOption selectEnrollProfileRemediationOption =
                    extractRemediationOption(remediationOptions, RemediationType.SELECT_ENROLL_PROFILE);

            EnrollRequest enrollRequest = EnrollRequestBuilder.builder()
                    .withStateHandle(stateHandle)
                    .build();

            // enroll new user
            IDXResponse enrollResponse = selectEnrollProfileRemediationOption.proceed(client, enrollRequest);

            RemediationOption[] enrollRemediationOptions = enrollResponse.remediation().remediationOptions();
            printRemediationOptions(enrollRemediationOptions);

            RemediationOption enrollProfileRemediationOption =
                    extractRemediationOption(enrollRemediationOptions, RemediationType.ENROLL_PROFILE);

            enrollProfileFormValues = Arrays.stream(enrollProfileRemediationOption.form())
                    .filter(x-> "userProfile".equals(x.getName()))
                    .collect(Collectors.toList());

            newUserRegistrationResponse.setFormValues(enrollProfileFormValues);
            newUserRegistrationResponse.setEnrollProfileRemediationOption(enrollProfileRemediationOption);

        } catch (ProcessingException e) {
            Arrays.stream(e.getErrorResponse().getMessages().getValue()).forEach(msg -> newUserRegistrationResponse.addError(msg.getMessage()));
            logger.error("Something went wrong! {}, {}", e, newUserRegistrationResponse.getErrors());
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
            newUserRegistrationResponse.addError(e.getMessage());
        }

        return newUserRegistrationResponse;
    }

    public static RemediationOption processRegistration(IDXClient client, RemediationOption remediationOption, UserProfile userProfile) {

        try {
            logger.info("Remediation Option: {} {}", remediationOption.form()[0].getName(), remediationOption.form()[0].getValue());

            Optional<FormValue> stateHandleOptional = Arrays.stream(remediationOption.form())
                    .filter(x-> "stateHandle".equals(x.getName())).findFirst();

            String newUserRegistrationStateHandle = String.valueOf(stateHandleOptional.get().getValue());

            EnrollUserProfileUpdateRequest enrollUserProfileUpdateRequest = EnrollUserProfileUpdateRequestBuilder.builder()
                    .withUserProfile(userProfile)
                    .withStateHandle(newUserRegistrationStateHandle)
                    .build();
            IDXResponse idxResponse = remediationOption.proceed(client, enrollUserProfileUpdateRequest);

            // check remediation options to go to the next step
            RemediationOption[] remediationOptions = idxResponse.remediation().remediationOptions();
            printRemediationOptions(remediationOptions);

            Optional<RemediationOption> remediationOptionsSelectAuthenticatorOptional = Arrays.stream(remediationOptions)
                    .filter(x -> RemediationType.SELECT_AUTHENTICATOR_ENROLL.equals(x.getName()))
                    .findFirst();
            remediationOption = remediationOptionsSelectAuthenticatorOptional.get();
            return remediationOption;

        } catch (ProcessingException e) {
            logger.error("Error", e);

        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
        }

        return null; //TODO
    }

    public static RemediationOption processEnrollAuthenticator(IDXClient idxClient, RemediationOption remediationOption, String authenticatorType) {

        Map<String, String> authenticatorOptions = remediationOption.getAuthenticatorOptions();
        logger.info("Authenticator Options: {}", authenticatorOptions);

        RemediationOption enrollRemediationOption = null;

        try {
            Authenticator authenticator = new Authenticator();

            if (authenticatorType.equals(AuthenticatorType.EMAIL.toString())) {
                authenticator.setId(authenticatorOptions.get(AuthenticatorType.EMAIL.toString()));
                authenticator.setMethodType(AuthenticatorType.EMAIL.toString());
            } else if (authenticatorType.equals(AuthenticatorType.PASSWORD.toString())) {
                authenticator.setId(authenticatorOptions.get(AuthenticatorType.PASSWORD.toString()));
                authenticator.setMethodType(AuthenticatorType.PASSWORD.toString());
            } else {
                String errMsg = "Unsupported authenticator " + authenticatorType;
                logger.error(errMsg);
                throw new IllegalArgumentException(errMsg);
            }

            Optional<FormValue> stateHandleOptional = Arrays.stream(remediationOption.form())
                    .filter(x -> "stateHandle" .equals(x.getName())).findFirst();
            String stateHandle = String.valueOf(stateHandleOptional.get().getValue());

            EnrollRequest enrollRequest = EnrollRequestBuilder.builder()
                    .withAuthenticator(authenticator)
                    .withStateHandle(stateHandle)
                    .build();

            IDXResponse idxResponse = remediationOption.proceed(idxClient, enrollRequest);

            RemediationOption[] enrollRemediationOptions = idxResponse.remediation().remediationOptions();
            printRemediationOptions(enrollRemediationOptions);

            enrollRemediationOption = extractRemediationOption(enrollRemediationOptions, RemediationType.ENROLL_AUTHENTICATOR);

        } catch (ProcessingException e) {
            logger.error("Error", e);
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
        }

        return enrollRemediationOption;
    }

    public static RemediationOption verifyEmailAuthenticator(IDXClient client, RemediationOption remediationOption, String passcode) {
        Credentials credentials = new Credentials();
        credentials.setPasscode(passcode.toCharArray());

        Optional<FormValue> stateHandleOptional = Arrays.stream(remediationOption.form())
                .filter(x-> "stateHandle".equals(x.getName())).findFirst();
        String stateHandle = String.valueOf(stateHandleOptional.get().getValue());

        // build answer password authenticator challenge request
        AnswerChallengeRequest challengeAuthenticatorRequest = AnswerChallengeRequestBuilder.builder()
                .withStateHandle(stateHandle)
                .withCredentials(credentials)
                .build();

        RemediationOption enrollRemediationOption = null;

        try {
            IDXResponse challengeAuthenticatorResponse =
                    remediationOption.proceed(client, challengeAuthenticatorRequest);

            if (challengeAuthenticatorResponse.remediation() != null) {
                RemediationOption[] remediationOptions = challengeAuthenticatorResponse.remediation().remediationOptions();
                printRemediationOptions(remediationOptions);

                // check if skip is present in remediation options, if yes skip it (we'll process only mandatory authenticators for now)
                try {
                    enrollRemediationOption = extractRemediationOption(remediationOptions, RemediationType.SKIP);
                } catch (IllegalArgumentException e) {
                    logger.warn("Skip authenticator not found in remediation option");
                    enrollRemediationOption = extractRemediationOption(remediationOptions, RemediationType.SELECT_AUTHENTICATOR_ENROLL);
                }
            }
        } catch (ProcessingException e) {
            logger.error("Error", e);
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
        }

        return enrollRemediationOption;
    }

    public static RemediationOption enrollPasswordAuthenticator(IDXClient client, RemediationOption remediationOption, String password) {

        Credentials credentials = new Credentials();
        credentials.setPasscode(password.toCharArray());

        Optional<FormValue> stateHandleOptional = Arrays.stream(remediationOption.form())
                .filter(x-> "stateHandle".equals(x.getName())).findFirst();
        String stateHandle = String.valueOf(stateHandleOptional.get().getValue());

        // build answer password authenticator challenge request
        AnswerChallengeRequest challengeAuthenticatorRequest = AnswerChallengeRequestBuilder.builder()
                .withStateHandle(stateHandle)
                .withCredentials(credentials)
                .build();

        RemediationOption enrollRemediationOption = null;

        try {
            IDXResponse challengeAuthenticatorResponse =
                    remediationOption.proceed(client, challengeAuthenticatorRequest);

            if (challengeAuthenticatorResponse.remediation() != null) {
                RemediationOption[] remediationOptions = challengeAuthenticatorResponse.remediation().remediationOptions();
                printRemediationOptions(remediationOptions);

                // check if skip is present in remediation options, if yes skip it (we'll process only mandatory authenticators for now)
                try {
                    enrollRemediationOption = extractRemediationOption(remediationOptions, RemediationType.SKIP);
                } catch (IllegalArgumentException e) {
                    logger.warn("Skip authenticator not found in remediation option");
                    enrollRemediationOption = extractRemediationOption(remediationOptions, RemediationType.SELECT_AUTHENTICATOR_ENROLL);
                }
            }

        } catch (ProcessingException e) {
            logger.error("Error", e);
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
        }

        return enrollRemediationOption;
    }

    public static IDXResponse skipAuthenticatorEnrollment(IDXClient client, RemediationOption remediationOption) {

        IDXResponse idxResponse = null;

        try {
            Optional<FormValue> stateHandleOptional = Arrays.stream(remediationOption.form())
                    .filter(x -> "stateHandle" .equals(x.getName())).findFirst();
            String stateHandle = String.valueOf(stateHandleOptional.get().getValue());

            SkipAuthenticatorEnrollmentRequest skipAuthenticatorEnrollmentRequest = SkipAuthenticatorEnrollmentRequestBuilder.builder()
                    .withStateHandle(stateHandle)
                    .build();

            idxResponse = remediationOption.proceed(client, skipAuthenticatorEnrollmentRequest);
        } catch (ProcessingException e) {
            logger.error("Error", e);
        } catch (IllegalArgumentException e) {
            logger.error("Exception occurred", e);
        }

        return idxResponse;
    }

    private static boolean isRemediationRequireCredentials(String remediationOptionName, IDXResponse idxResponse) {
        RemediationOption[] remediationOptions = idxResponse.remediation().remediationOptions();

        Optional<RemediationOption> remediationOptionsOptional = Arrays.stream(remediationOptions)
                .filter(x -> remediationOptionName.equals(x.getName()))
                .findFirst();
        Assert.isTrue(remediationOptionsOptional.isPresent(), "Missing remediation option " + remediationOptionName);

        RemediationOption remediationOption = remediationOptionsOptional.get();
        FormValue[] formValues = remediationOption.form();

        Optional<FormValue> credentialsFormValueOptional = Arrays.stream(formValues)
                .filter(x -> "credentials".equals(x.getName()))
                .findFirst();

        return credentialsFormValueOptional.isPresent();
    }

    private static RemediationOption extractRemediationOption(RemediationOption[] remediationOptions, String remediationType) {
        Optional<RemediationOption> remediationOptionsOptional = Arrays.stream(remediationOptions)
                .filter(x -> remediationType.equals(x.getName()))
                .findFirst();
        Assert.isTrue(remediationOptionsOptional.isPresent(), "Missing remediation option " + remediationType);
        return remediationOptionsOptional.get();
    }

    private static void printRemediationOptions(RemediationOption[] remediationOptions) {
        logger.info("Remediation Options: {}", Arrays.stream(remediationOptions)
                .map(RemediationOption::getName)
                .collect(Collectors.toList()));
    }
}
