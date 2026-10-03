package com.amanahconnect.mail;

/** A finished email: subject, HTML and plain-text bodies, and the branding it was rendered with (its logo is attached inline). */
public record RenderedMail(String subject, String html, String text, MailBranding branding, MailTemplate template) {}
