package com.amanahconnect.mail;

import com.amanahconnect.auth.AuthProperties;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.mail.MailTemplate.Audience;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Works out the header and footer of an email: the community's for member mail, the platform's otherwise. */
@Component
public class BrandingResolver {

    private static final Logger log = LoggerFactory.getLogger(BrandingResolver.class);
    private static final int LOGO_HEIGHT = 120;
    private static final long MAX_LOGO_BYTES = 600_000;

    private final CommunityRepository communities;
    private final ObjectStorage storage;
    private final AuthProperties authProperties;
    private volatile byte[] platformLogo;

    public BrandingResolver(CommunityRepository communities, ObjectStorage storage, AuthProperties authProperties) {
        this.communities = communities;
        this.storage = storage;
        this.authProperties = authProperties;
    }

    @Transactional(readOnly = true)
    public MailBranding resolve(UUID communityId, Audience audience) {
        if (audience == Audience.MEMBER && communityId != null) {
            Community community = communities.findById(communityId).orElse(null);
            if (community != null) return forCommunity(community);
            log.warn("Community {} not found for a member email; using the platform's branding", communityId);
        }
        return platform(audience);
    }

    public MailBranding platform(Audience audience) {
        return new MailBranding(audience, "Amanah Connect", platformLogo(), "image/png", null, null, null, null, appUrl());
    }

    public MailBranding forCommunity(Community c) {
        byte[] logo = null;
        String type = null;
        if (c.getLogoKey() != null) {
            try {
                logo = storage.get(c.getLogoKey()).filter(b -> b.length <= MAX_LOGO_BYTES).orElse(null);
                if (logo != null) {
                    String key = c.getLogoKey().toLowerCase();
                    type = key.endsWith(".png") ? "image/png" : key.endsWith(".webp") ? "image/webp" : "image/jpeg";
                    byte[] small = thumbnail(logo);
                    if (small != null) {
                        logo = small;
                        type = "image/png";
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Logo of community {} could not be loaded for an email ({}); sending without it", c.getId(), e.toString());
                logo = null;
            }
        }
        List<String> address = new ArrayList<>();
        for (String part : new String[] {c.getAddressLine1(), c.getAddressLine2(), c.getCity(), c.getState(), c.getPostalCode()}) {
            if (part != null && !part.isBlank()) address.add(part.trim());
        }
        return new MailBranding(Audience.MEMBER, c.getName(), logo, type, c.getContactEmail(), c.getContactPhone(), String.join(", ", address), c.getContactEmail(), appUrl());
    }

    private String appUrl() {
        return authProperties.frontendBaseUrl().replaceAll("/+$", "");
    }

    private byte[] platformLogo() {
        byte[] cached = platformLogo;
        if (cached == null) {
            try (InputStream in = BrandingResolver.class.getResourceAsStream("/mail/logo-mark.png")) {
                cached = in == null ? new byte[0] : in.readAllBytes();
            } catch (IOException e) {
                cached = new byte[0];
            }
            platformLogo = cached;
        }
        return cached.length == 0 ? null : cached;
    }

    /** Scales a PNG or JPEG down to header size; null when it cannot be read (WebP, or damaged), so the original is used. */
    static byte[] thumbnail(byte[] original) {
        try {
            BufferedImage in = ImageIO.read(new ByteArrayInputStream(original));
            if (in == null || in.getHeight() <= LOGO_HEIGHT) return null;
            int w = Math.max(1, in.getWidth() * LOGO_HEIGHT / in.getHeight());
            BufferedImage out = new BufferedImage(w, LOGO_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            var g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(in.getScaledInstance(w, LOGO_HEIGHT, Image.SCALE_AREA_AVERAGING), 0, 0, null);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
