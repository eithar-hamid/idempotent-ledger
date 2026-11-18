package io.github.eitharhamid.ledger;

import org.springframework.boot.SpringApplication;

public class TestIdempotentLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(IdempotentLedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
