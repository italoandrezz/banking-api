package com.italo.bankingapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class BankingApiServiceApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
		assertEquals("banking_api_tests",
				jdbcTemplate.queryForObject("SELECT current_schema()", String.class));
		assertEquals(4, jdbcTemplate.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'banking_api_tests'
				AND table_name IN ('customers', 'addresses', 'accounts', 'transactions')
				""", Integer.class));
		assertEquals(1, jdbcTemplate.queryForObject("""
				SELECT count(*) FROM banking_api_tests.flyway_schema_history
				WHERE version = '1' AND success
				""", Integer.class));
	}

}
