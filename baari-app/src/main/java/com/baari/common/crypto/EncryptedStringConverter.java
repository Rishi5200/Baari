package com.baari.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Encrypts a String column on its way to the database and decrypts it on the way back. Use on
 * an entity field with {@code @Convert(converter = EncryptedStringConverter.class)}.
 *
 * <p>A Spring bean so the key can be injected: Spring Boot hands Hibernate a bean container,
 * and Hibernate asks it for converter instances.
 *
 * <p>{@code null} stays {@code null}: the retention job clears PII by setting the column to null.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, byte[]> {

    private final FieldCipher cipher;

    public EncryptedStringConverter(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
