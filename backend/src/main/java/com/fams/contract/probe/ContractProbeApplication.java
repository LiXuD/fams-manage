package com.fams.contract.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fams")
public class ContractProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractProbeApplication.class, args);
    }
}
