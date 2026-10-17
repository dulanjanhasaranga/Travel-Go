package com.travelgo.service;

import com.travelgo.entity.PackageCategory;
import com.travelgo.repository.PackageCategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PackageCategoryService {

    private final PackageCategoryRepository repository;

    public PackageCategoryService(PackageCategoryRepository repository) {
        this.repository = repository;
    }

    public List<PackageCategory> findAll() {
        return repository.findAll();
    }

    public Optional<PackageCategory> findById(Long id) {
        return repository.findById(id);
    }

    public PackageCategory save(PackageCategory entity) {
        entity.setName(CatalogueInput.required(entity.getName(),"Category name"));
        CatalogueInput.text(entity.getDescription(),255,"Description");
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        setActive(id,false);
    }
    @org.springframework.transaction.annotation.Transactional
    public void setActive(Long id,boolean active) {
        PackageCategory category=repository.findById(id).orElseThrow(()->new IllegalArgumentException("Category not found."));
        category.setActive(active); repository.save(category);
    }
}
