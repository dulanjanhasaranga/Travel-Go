package com.travelgo.service;

import com.travelgo.entity.Transport;
import com.travelgo.repository.TransportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TransportService {
    private final TransportRepository repository;

    public TransportService(TransportRepository repository) {
        this.repository = repository;
    }

    public List<Transport> findAll() {
        return repository.findAll();
    }

    public List<Transport> findActive() {
        return repository.findByIsActiveTrue();
    }

    public Optional<Transport> findById(Long id) {
        return repository.findById(id);
    }

    public Transport save(Transport transport) {
        return repository.save(transport);
    }

    public void deactivate(Long id) {
        repository.findById(id).ifPresent(transport -> {
            transport.setActive(false);
            repository.save(transport);
        });
    }
}
