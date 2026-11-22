package com.bmp.catalog.application.command;

import com.bmp.catalog.domain.model.ConversionSource;
import java.math.BigDecimal;

/**
 * A product conversion as a request gives it: {@code 1 fromUnit = factor toUnit}.
 *
 * @param fromUnit unit code
 * @param toUnit unit code
 * @param factor positive
 * @param source where the factor comes from
 */
public record ConversionSpec(String fromUnit, String toUnit, BigDecimal factor, ConversionSource source) {}
