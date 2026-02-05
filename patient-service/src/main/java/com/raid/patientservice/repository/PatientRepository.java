package com.raid.patientservice.repository;

import com.raid.patientservice.model.Patient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {
    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(@NotBlank(message = "Email is required") @Email(message = "Email should be valid") String email, UUID id);
}