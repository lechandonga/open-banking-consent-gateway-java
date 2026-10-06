package com.github.lechandonga.openbankingconsentgatewayjava.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.lechandonga.openbankingconsentgatewayjava.common.ApiException;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.Consent;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.ConsentService;

@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateConsentRequest request) {
        try {
            Consent consent = consentService.create(request.customerId(), Set.copyOf(request.scopes()),
                    request.ttlSeconds());
            return ResponseEntity.status(HttpStatus.CREATED).body(toView(consent));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
        }
    }

    @PostMapping("/{id}/authorize")
    public Map<String, Object> authorize(@PathVariable String id) {
        return toView(doTransition(id, true));
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id) {
        return toView(doTransition(id, false));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toView(getConsent(id));
    }

    @PostMapping("/{id}/access")
    public Map<String, Object> access(@PathVariable String id, @RequestParam String scope) {
        Consent consent = consentService.checkAccess(id, scope);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allowed", true);
        body.put("consentId", consent.getId());
        body.put("customerId", consent.getCustomerId());
        body.put("scope", scope);
        return body;
    }

    private Consent doTransition(String id, boolean authorize) {
        try {
            return authorize ? consentService.authorize(id) : consentService.revoke(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONSENT_NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
        }
    }

    private Consent getConsent(String id) {
        try {
            return consentService.get(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONSENT_NOT_FOUND", e.getMessage());
        }
    }

    static Map<String, Object> toView(Consent consent) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", consent.getId());
        view.put("customerId", consent.getCustomerId());
        view.put("scopes", consent.getScopes());
        view.put("status", consent.getStatus());
        view.put("createdAt", consent.getCreatedAt());
        view.put("expiresAt", consent.getExpiresAt());
        if (consent.getAuthorizedAt() != null) {
            view.put("authorizedAt", consent.getAuthorizedAt());
        }
        if (consent.getRevokedAt() != null) {
            view.put("revokedAt", consent.getRevokedAt());
        }
        return view;
    }

    public record CreateConsentRequest(String customerId, Set<String> scopes, long ttlSeconds) {
    }
}
