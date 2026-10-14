package com.example.datasync;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A test of the whole datasync application: its own embedded H2 with the Taskboard schema, the
 * {@link HostStub} as the CICS host, no schedules, MockMvc for the API. All such tests share one
 * application context; each one clears the database and resets the stub first.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DatasyncTestConfiguration.class)
public @interface DatasyncTest {}
