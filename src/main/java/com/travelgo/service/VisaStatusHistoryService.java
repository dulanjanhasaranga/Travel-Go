package com.travelgo.service;

import com.travelgo.entity.VisaStatusHistory;
import com.travelgo.repository.VisaStatusHistoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class VisaStatusHistoryService {

    private final VisaStatusHistoryRepository repository;

    public VisaStatusHistoryService(VisaStatusHistoryRepository repository) {
        this.repository = repository;
    }

    public List<VisaStatusHistory> findAll() {
        return repository.findAll();
    }

    public Optional<VisaStatusHistory> findById(Long id) {
        return repository.findById(id);
    }

    public VisaStatusHistory save(VisaStatusHistory entity) {
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
