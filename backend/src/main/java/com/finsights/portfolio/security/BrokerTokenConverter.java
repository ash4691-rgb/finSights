package com.finsights.portfolio.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Transparently encrypts/decrypts a {@link com.finsights.portfolio.domain.BrokerConnection}
 *  access/refresh token on the way to and from the database — see {@link BrokerTokenEncryption}
 *  for the actual key handling. {@code autoApply = false}: only columns explicitly annotated
 *  {@code @Convert(converter = BrokerTokenConverter.class)} go through this, never every String
 *  column in the schema by accident. */
@Converter(autoApply = false)
public class BrokerTokenConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : BrokerTokenEncryption.encryptorFromEnv().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : BrokerTokenEncryption.encryptorFromEnv().decrypt(dbData);
    }
}
