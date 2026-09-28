package com.leoneferito;

import org.springframework.boot.SpringApplication;

public class TestLeoneferitoApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(LeoneferitoApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
