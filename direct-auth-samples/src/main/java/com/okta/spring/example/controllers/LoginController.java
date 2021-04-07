/*
 * Copyright 2017 Okta, Inc.
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
package com.okta.spring.example.controllers;

import com.okta.idx.sdk.api.client.Clients;
import com.okta.idx.sdk.api.client.IDXClient;
import com.okta.idx.sdk.api.model.AuthenticationOptions;
import com.okta.idx.sdk.api.model.AuthenticationStatus;
import com.okta.idx.sdk.api.model.AuthenticatorType;
import com.okta.idx.sdk.api.model.AuthenticatorUIOption;
import com.okta.idx.sdk.api.model.ChangePasswordOptions;
import com.okta.idx.sdk.api.model.FormValue;
import com.okta.idx.sdk.api.model.IDXClientContext;
import com.okta.idx.sdk.api.model.RecoverPasswordOptions;
import com.okta.idx.sdk.api.model.RemediationOption;
import com.okta.idx.sdk.api.model.RemediationType;
import com.okta.idx.sdk.api.model.UserProfile;
import com.okta.idx.sdk.api.model.VerifyAuthenticatorOptions;
import com.okta.idx.sdk.api.response.AuthenticationResponse;
import com.okta.idx.sdk.api.response.IDXResponse;
import com.okta.idx.sdk.api.response.NewUserRegistrationResponse;
import com.okta.idx.sdk.api.wrapper.AuthenticationWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpSession;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Controller
public class LoginController {

    private final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private static final IDXClient client = Clients.builder().build();

    @PostMapping("/custom-login")
    public ModelAndView postLogin(@RequestParam("username") String username,
                                  @RequestParam("password") String password,
                                  HttpSession session) {

        AuthenticationResponse authenticationResponse =
                (AuthenticationResponse) session.getAttribute("authenticationResponse");

        // render existing auth response if a successful one is already present in session
        if (authenticationResponse != null &&
            authenticationResponse.getAuthenticationStatus() == AuthenticationStatus.SUCCESS) {
            ModelAndView mav = new ModelAndView("home");
            mav.addObject("authenticationResponse", authenticationResponse);
            return mav;
        }

        // trigger authentication
        authenticationResponse = AuthenticationWrapper.authenticate(client, new AuthenticationOptions(username, password));

        // populate login view with errors
        if (authenticationResponse.getAuthenticationStatus() != AuthenticationStatus.SUCCESS) {
            ModelAndView modelAndView = new ModelAndView("login");
            modelAndView.addObject("errors", authenticationResponse.getErrors());
            return modelAndView;
        }

        // success
        ModelAndView mav = new ModelAndView("home");
        mav.addObject("authenticationResponse", authenticationResponse);

        // store attributes in session
        session.setAttribute("user", username);
        session.setAttribute("authenticationResponse", authenticationResponse);
        return mav;
    }

    @PostMapping("/forgot-password")
    public ModelAndView postForgotPassword(@RequestParam("username") String username,
                                           @RequestParam("authenticatorType") String authenticatorType,
                                           HttpSession httpSession) {
        logger.info(":: Forgot Password ::");

        //TODO
        AuthenticationResponse authenticationResponse =
                AuthenticationWrapper.recoverPassword(client, new RecoverPasswordOptions(username, AuthenticatorType.EMAIL));

        if (authenticationResponse.getAuthenticationStatus() == null) {
            ModelAndView mav = new ModelAndView("forgotpassword");
            mav.addObject("result", authenticationResponse.getErrors());
            return mav;
        }

        logger.info("Authentication status: {}", authenticationResponse.getAuthenticationStatus());

        if (authenticationResponse.getAuthenticationStatus().equals(AuthenticationStatus.AWAITING_AUTHENTICATOR_VERIFICATION)) {
            httpSession.setAttribute("idxClientContext", authenticationResponse.getIdxClientContext());
            return new ModelAndView("verify");
        }

        return null; //TODO
    }

    @PostMapping("/verify")
    public ModelAndView postVerify(@RequestParam("code") String code,
                                   HttpSession httpSession) {
        logger.info(":: Verify Code :: {}", code);

        IDXClientContext idxClientContext = (IDXClientContext) httpSession.getAttribute("idxClientContext");

        VerifyAuthenticatorOptions verifyAuthenticatorOptions = new VerifyAuthenticatorOptions();
        verifyAuthenticatorOptions.setCode(code);

        AuthenticationResponse authenticationResponse =
                AuthenticationWrapper.verifyAuthenticator(client, idxClientContext, verifyAuthenticatorOptions);

        logger.info("Authentication status: {}", authenticationResponse.getAuthenticationStatus());

        if (authenticationResponse.getAuthenticationStatus() == AuthenticationStatus.AWAITING_PASSWORD_RESET) {
            ModelAndView modelAndView = new ModelAndView("changepassword");
            return modelAndView;
        }

        ModelAndView modelAndView = new ModelAndView("login");
        modelAndView.addObject("messages", authenticationResponse.getAuthenticationStatus().toString());
        return modelAndView;
    }

    @PostMapping("/change-password")
    public ModelAndView postChangePassword(@RequestParam("new-password") String newPassword,
                                           @RequestParam("confirm-new-password") String confirmNewPassword,
                                           HttpSession httpSession) {
        logger.info(":: Change Password ::");

        if (!newPassword.equals(confirmNewPassword)) {
            ModelAndView modelAndView = new ModelAndView("changepassword");
            modelAndView.addObject("result", "Passwords do not match");
            return modelAndView;
        }

        ModelAndView modelAndView = new ModelAndView("login");

        IDXClientContext idxClientContext = (IDXClientContext) httpSession.getAttribute("idxClientContext");

        ChangePasswordOptions changePasswordOptions = new ChangePasswordOptions();
        changePasswordOptions.setNewPassword(newPassword);

        AuthenticationResponse authenticationResponse =
                AuthenticationWrapper.changePassword(client, idxClientContext, changePasswordOptions);

        logger.info("Authentication status: {}", authenticationResponse.getAuthenticationStatus());

        modelAndView.addObject("info", authenticationResponse.getAuthenticationStatus().toString());

        return modelAndView;
    }

    @PostMapping("/register")
    public ModelAndView postRegister(@RequestParam("lastname") String lastname,
                                     @RequestParam("firstname") String firstname,
                                     @RequestParam("email") String email,
                                     HttpSession session) {
        logger.info(":: Register ::");

        ModelAndView modelAndView = new ModelAndView("enroll-authenticators");

        NewUserRegistrationResponse newUserRegistrationResponse = AuthenticationWrapper.fetchSignUpFormValues(client);

        for (FormValue formValue : newUserRegistrationResponse.getFormValues()) {
            for (FormValue userProfileFormValue : formValue.getForm().getValue()) {
                if (userProfileFormValue.isRequired()) {
                    logger.info("Adding object {}", userProfileFormValue.getName());
                    modelAndView.addObject(userProfileFormValue);
                }
            }
        }

        //TODO: set this dynamically (for initial flow, only these three fields will be configured)
        UserProfile userProfile = new UserProfile();
        userProfile.addAttribute("lastName", lastname);
        userProfile.addAttribute("firstName", firstname);
        userProfile.addAttribute("email", email);

        RemediationOption enrollAuthenticatorRemediationOption =
                AuthenticationWrapper.processRegistration(client, newUserRegistrationResponse.getEnrollProfileRemediationOption(), userProfile);

        session.setAttribute("enrollAuthenticatorRemediationOption", enrollAuthenticatorRemediationOption);
        session.setAttribute("email", email);

        Map<String, String> authenticatorOptions = enrollAuthenticatorRemediationOption.getAuthenticatorOptions();

        List<AuthenticatorUIOption> authenticatorUIOptionList = new LinkedList<>();

        for (Map.Entry<String, String> entry : authenticatorOptions.entrySet()) {
            authenticatorUIOptionList.add(new AuthenticatorUIOption(entry.getValue(), entry.getKey()));
        }

        modelAndView.addObject("authenticatorUIOptionList", authenticatorUIOptionList);
        return modelAndView;
    }

    @PostMapping(value = "/enroll-authenticator")
    public ModelAndView postEnrollAuthenticator(@RequestParam("authenticator-type") String authenticatorType,
                                                HttpSession session) {
        logger.info(":: Enroll Authenticator ::");

        RemediationOption remediationOption = (RemediationOption) session.getAttribute("enrollAuthenticatorRemediationOption");

        RemediationOption enrollResponseRemediationOption =
                AuthenticationWrapper.processEnrollAuthenticator(client, remediationOption, authenticatorType);

        session.setAttribute("enrollResponseRemediationOption", enrollResponseRemediationOption);

        // TODO deal error

        if (authenticatorType.equals(AuthenticatorType.EMAIL.toString())) {
            return new ModelAndView("verify-email-authenticator-enrollment");
        } else if (authenticatorType.equals(AuthenticatorType.PASSWORD.toString())) {
            return new ModelAndView("password-authenticator-enrollment");
        } else {
            logger.error("Unsupported authenticator {}", authenticatorType);
            return null; //TODO
        }
    }

    @PostMapping(value = "/verify-email-authenticator-enrollment")
    public ModelAndView verifyEmailAuthenticator(@RequestParam("code") String code,
                                                 HttpSession session) {
        logger.info(":: Verify Email Authenticator :: {}", code);

        RemediationOption remediationOption = (RemediationOption) session.getAttribute("enrollResponseRemediationOption");

        RemediationOption emailAuthenticatorVerificationResponseRemediation =
                AuthenticationWrapper.verifyEmailAuthenticator(client, remediationOption, code);

        if (emailAuthenticatorVerificationResponseRemediation == null) {
            ModelAndView modelAndView = new ModelAndView("login");
            modelAndView.addObject("info", "Registration successful");
            return modelAndView;
        }

        if (emailAuthenticatorVerificationResponseRemediation.getName().equals(RemediationType.SKIP)) {
            IDXResponse idxResponse =
                    AuthenticationWrapper.skipAuthenticatorEnrollment(client, emailAuthenticatorVerificationResponseRemediation);

            if (idxResponse.isLoginSuccessful()) {
                ModelAndView modelAndView = new ModelAndView("login");
                modelAndView.addObject("info", "Registration successful");
                return modelAndView;
            }
        }

        session.setAttribute("enrollResponseRemediationOption", emailAuthenticatorVerificationResponseRemediation);

        ModelAndView modelAndView = new ModelAndView("enroll-authenticators");
        Map<String, String> authenticatorOptions = emailAuthenticatorVerificationResponseRemediation.getAuthenticatorOptions();

        List<AuthenticatorUIOption> authenticatorUIOptionList = new LinkedList<>();

        for (Map.Entry<String, String> entry : authenticatorOptions.entrySet()) {
            authenticatorUIOptionList.add(new AuthenticatorUIOption(entry.getValue(), entry.getKey()));
        }

        modelAndView.addObject("authenticatorUIOptionList", authenticatorUIOptionList);
        return modelAndView;
    }

    @PostMapping(value = "/password-authenticator-enrollment")
    public ModelAndView enrollPasswordAuthenticator(@RequestParam("new-password") String newPassword,
                                                    @RequestParam("confirm-new-password") String confirmNewPassword,
                                                    HttpSession session) {
        logger.info(":: Enroll Password Authenticator ::");

        if (!newPassword.equals(confirmNewPassword)) {
            ModelAndView modelAndView = new ModelAndView("password-authenticator-enrollment");
            modelAndView.addObject("result", "Passwords do not match");
            return modelAndView;
        }

        RemediationOption remediationOption = (RemediationOption) session.getAttribute("enrollResponseRemediationOption");

        RemediationOption passwordAuthenticatorVerificationResponseRemediation =
                AuthenticationWrapper.enrollPasswordAuthenticator(client, remediationOption, confirmNewPassword);

        if (passwordAuthenticatorVerificationResponseRemediation == null) {
            ModelAndView modelAndView = new ModelAndView("login");
            modelAndView.addObject("info", "Registration successful");
            return modelAndView;
        }

        if (passwordAuthenticatorVerificationResponseRemediation.getName().equals(RemediationType.SKIP)) {
            IDXResponse idxResponse =
                    AuthenticationWrapper.skipAuthenticatorEnrollment(client, passwordAuthenticatorVerificationResponseRemediation);

            if (idxResponse.isLoginSuccessful()) {
                ModelAndView modelAndView = new ModelAndView("login");
                modelAndView.addObject("info", "Registration successful");
                return modelAndView;
            }
        }

        ModelAndView modelAndView = new ModelAndView("enroll-authenticators");
        Map<String, String> authenticatorOptions = passwordAuthenticatorVerificationResponseRemediation.getAuthenticatorOptions();

        List<AuthenticatorUIOption> authenticatorUIOptionList = new LinkedList<>();

        for (Map.Entry<String, String> entry : authenticatorOptions.entrySet()) {
            authenticatorUIOptionList.add(new AuthenticatorUIOption(entry.getValue(), entry.getKey()));
        }

        modelAndView.addObject("authenticatorUIOptionList", authenticatorUIOptionList);
        return modelAndView;
    }
}