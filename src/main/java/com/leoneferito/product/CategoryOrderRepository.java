package com.leoneferito.product;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryOrderRepository extends JpaRepository<CategoryOrder, ProductCategory> {

    List<CategoryOrder> findAllByOrderBySortOrderAsc();
}