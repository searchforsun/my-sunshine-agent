package com.sunshine.model.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.model.dto.ModelProviderRequest;
import com.sunshine.model.dto.ModelProviderResponse;
import com.sunshine.model.entity.ModelProviderEntity;
import com.sunshine.model.event.ModelCatalogChangePublisher;
import com.sunshine.model.exception.ModelErrorCode;
import com.sunshine.model.repo.ModelProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelProviderService {
    private final ModelProviderRepository providerRepository;
    private final ModelCryptoService cryptoService;
    private final ModelCatalogChangePublisher catalogChangePublisher;

    public List<ModelProviderResponse> list(String tenantId) {
        String tid = ModelJsonSupport.normalizeTenantId(tenantId);
        return providerRepository.findByTenantIdOrderByProviderKeyAsc(tid).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ModelProviderResponse create(ModelProviderRequest request) {
        if (!StringUtils.hasText(request.providerKey())) {
            throw new BizException(ModelErrorCode.PROVIDER_KEY_REQUIRED);
        }
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(ModelErrorCode.PROVIDER_DISPLAY_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new BizException(ModelErrorCode.PROVIDER_BASE_URL_REQUIRED);
        }
        String tid = ModelJsonSupport.normalizeTenantId(request.tenantId());
        String providerKey = request.providerKey().strip();
        if (providerRepository.existsByTenantIdAndProviderKey(tid, providerKey)) {
            throw new BizException(ModelErrorCode.PROVIDER_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setProviderKey(providerKey);
        entity.setDisplayName(request.displayName().strip());
        entity.setProtocol(StringUtils.hasText(request.protocol()) ? request.protocol().strip() : "openai-compatible");
        entity.setBaseUrl(request.baseUrl().strip());
        entity.setPathPrefix(request.pathPrefix() != null ? request.pathPrefix().strip() : "");
        entity.setApiKeyEnc(cryptoService.encrypt(request.apiKey()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setTenantId(tid);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        providerRepository.save(entity);
        catalogChangePublisher.publish(tid);
        return toResponse(entity);
    }

    @Transactional
    public ModelProviderResponse update(Long id, ModelProviderRequest request) {
        ModelProviderEntity entity = require(id);
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(ModelErrorCode.PROVIDER_DISPLAY_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new BizException(ModelErrorCode.PROVIDER_BASE_URL_REQUIRED);
        }
        entity.setDisplayName(request.displayName().strip());
        if (StringUtils.hasText(request.protocol())) {
            entity.setProtocol(request.protocol().strip());
        }
        entity.setBaseUrl(request.baseUrl().strip());
        if (request.pathPrefix() != null) {
            entity.setPathPrefix(request.pathPrefix().strip());
        }
        // 空 apiKey 表示保留原密文，避免管理面「只改 URL」误清空密钥
        if (StringUtils.hasText(request.apiKey())) {
            entity.setApiKeyEnc(cryptoService.encrypt(request.apiKey()));
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        entity.setUpdatedAt(Instant.now());
        providerRepository.save(entity);
        catalogChangePublisher.publish(entity.getTenantId());
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long id) {
        ModelProviderEntity entity = require(id);
        String tid = entity.getTenantId();
        providerRepository.delete(entity);
        catalogChangePublisher.publish(tid);
    }

    private ModelProviderEntity require(Long id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new BizException(ModelErrorCode.PROVIDER_NOT_FOUND));
    }

    private ModelProviderResponse toResponse(ModelProviderEntity entity) {
        boolean configured = cryptoService.isConfigured(entity.getApiKeyEnc());
        return new ModelProviderResponse(
                entity.getId(),
                entity.getProviderKey(),
                entity.getDisplayName(),
                entity.getProtocol(),
                entity.getBaseUrl(),
                entity.getPathPrefix(),
                entity.isEnabled(),
                entity.getTenantId(),
                configured,
                cryptoService.maskForAdmin(entity.getApiKeyEnc()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
