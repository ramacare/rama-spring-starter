package org.rama.autoconfigure;

import org.bson.types.Decimal128;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;

/**
 * Registers MongoDB custom converters (OffsetDateTime, BigInteger/Decimal128)
 * before Spring Boot's DataMongoAutoConfiguration so they are picked up
 * by the default MongoTemplate.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoTemplate.class)
public class RamaMongoConversionsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(Arrays.asList(
                new OffsetDateTimeReadConverter(),
                new OffsetDateTimeWriteConverter(),
                new BigIntegerToDecimal128Converter(),
                new Decimal128ToBigIntegerConverter()
        ));
    }

    static class OffsetDateTimeWriteConverter implements Converter<OffsetDateTime, Date> {
        @Override
        public Date convert(OffsetDateTime source) {
            return Date.from(source.toInstant());
        }
    }

    static class OffsetDateTimeReadConverter implements Converter<Date, OffsetDateTime> {
        @Override
        public OffsetDateTime convert(Date source) {
            return source.toInstant().atOffset(ZoneOffset.UTC);
        }
    }

    static class BigIntegerToDecimal128Converter implements Converter<BigInteger, Decimal128> {
        @Override
        public Decimal128 convert(BigInteger source) {
            return new Decimal128(new java.math.BigDecimal(source));
        }
    }

    static class Decimal128ToBigIntegerConverter implements Converter<Decimal128, BigInteger> {
        @Override
        public BigInteger convert(Decimal128 source) {
            return source.bigDecimalValue().toBigInteger();
        }
    }
}
