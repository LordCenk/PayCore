package com.paycore.merchant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

    Optional<Merchant> findByApiKeyHash(String apiKeyHash);

    boolean existsByEmail(String email);
}
