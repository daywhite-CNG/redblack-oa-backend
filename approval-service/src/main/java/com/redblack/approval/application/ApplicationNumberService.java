package com.redblack.approval.application;

import com.redblack.approval.infrastructure.persistence.ApplicationNoMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class ApplicationNumberService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final ApplicationNoMapper mapper;
    private final Clock clock;

    public ApplicationNumberService(ApplicationNoMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public String next() {
        LocalDate date = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        mapper.initialize(date);
        if (mapper.increment(date) != 1) {
            throw new IllegalStateException("Unable to increment leave application number");
        }
        long sequence = mapper.currentConnectionValue();
        if (sequence > 9999) {
            throw new IllegalStateException("Daily leave application number limit exceeded");
        }
        return "LV" + DATE.format(date) + String.format("%04d", sequence);
    }
}
