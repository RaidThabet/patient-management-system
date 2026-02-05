package com.raid.patientservice.controller;

import com.raid.patientservice.dto.PatientRequestDTO;
import com.raid.patientservice.dto.PatientResponseDTO;
import com.raid.patientservice.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @GetMapping
    public ResponseEntity<List<PatientResponseDTO>> getPatients() {
        List<PatientResponseDTO> patients = patientService.getPatients();
        return ResponseEntity.ok(patients);
    }

    @PostMapping
    public ResponseEntity<PatientResponseDTO> createPatient(
            @Valid @RequestBody PatientRequestDTO patientRequestDTO
    ) {
        PatientResponseDTO createdPatient = patientService.createPatient(patientRequestDTO);
        return ResponseEntity.ok(createdPatient);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PatientResponseDTO> updatePatient(
            @PathVariable String id,
            @Valid @RequestBody PatientRequestDTO patientRequestDTO
    ) {
        PatientResponseDTO updatedPatient = patientService.updatePatient(java.util.UUID.fromString(id), patientRequestDTO);
        return ResponseEntity.ok(updatedPatient);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePatient(@PathVariable String id) {
        patientService.deletePatient(java.util.UUID.fromString(id));
        return ResponseEntity.noContent().build();
    }
}
