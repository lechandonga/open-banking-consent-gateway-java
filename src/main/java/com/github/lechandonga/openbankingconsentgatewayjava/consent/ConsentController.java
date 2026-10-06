package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import com.github.lechandonga.openbankingconsentgatewayjava.consent.dto.AccessRequest;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.dto.ConsentView;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.dto.CreateConsentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentView create(@RequestBody CreateConsentRequest request) {
        if (request.getClientId() == null || request.getClientId().isBlank()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        if (request.getScopes() == null || request.getScopes().isEmpty()) {
            throw new IllegalArgumentException("scopes 不能为空");
        }
        return new ConsentView(consentService.createConsent(
                request.getClientId(), request.getScopes(), request.getTtlSeconds()));
    }

    @PostMapping("/{consentId}/authorize")
    public ConsentView authorize(@PathVariable String consentId) {
        return new ConsentView(consentService.authorize(consentId));
    }

    @PostMapping("/{consentId}/revoke")
    public ConsentView revoke(@PathVariable String consentId) {
        return new ConsentView(consentService.revoke(consentId));
    }

    @GetMapping("/{consentId}")
    public ConsentView get(@PathVariable String consentId) {
        return new ConsentView(consentService.get(consentId));
    }

    /**
     * 访问校验接口：供网关内部/联调验证授权是否可访问。
     */
    @PostMapping("/access-check")
    public ResponseEntity<Map<String, Object>> accessCheck(@RequestBody AccessRequest request) {
        Set<String> scopes = request.getScopes() == null ? Set.of() : request.getScopes();
        consentService.checkAccess(request.getConsentId(), request.getClientId(), scopes);
        return ResponseEntity.ok(Map.of("allowed", true, "consentId", request.getConsentId()));
    }
}
