package io.github.eitharhamid.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IdempotentLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.run(IdempotentLedgerApplication.class, args);
	}

}
