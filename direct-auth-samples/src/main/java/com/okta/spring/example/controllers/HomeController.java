package com.okta.spring.example.controllers;

import com.okta.idx.sdk.api.model.AuthenticatorUIOption;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpSession;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/custom-login";
    }

    @GetMapping(value = "/custom-login")
    public ModelAndView getLogin() {
        return new ModelAndView("login");
    }

    @GetMapping("/forgot-password")
    public ModelAndView getForgotPassword() {
        return new ModelAndView("forgotpassword");
    }


    @GetMapping("/register")
    public ModelAndView getRegister() {
        return new ModelAndView("register");
    }

    @GetMapping("/enroll-authenticators")
    public String getEnrollAuthenticators(Model model) {
        model.addAttribute("authenticatorUIOption", new AuthenticatorUIOption());
        return "enroll-authenticators";
    }

    @GetMapping("/verify-email-authenticator-enrollment")
    public ModelAndView getVerifyEmailAuthenticatorEnrollment() {
        return new ModelAndView("verify-email-authenticator-enrollment");
    }

    @GetMapping("/password-authenticator-enrollment")
    public ModelAndView getPasswordAuthenticatorEnrollment() {
        return new ModelAndView("password-authenticator-enrollment");
    }

    @GetMapping("/logout")
    public String logout(HttpSession session ) {
        session.invalidate();
        return "redirect:/custom-login";
    }
}