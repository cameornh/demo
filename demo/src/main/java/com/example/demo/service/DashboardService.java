package com.example.demo.service;

import com.example.demo.dto.InventoryAlertDTO;
import com.example.demo.model.Ingredient;
import com.example.demo.repository.IngredientRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardService {
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<InventoryAlertDTO> getManagerDashboard(int locationId) {
        entityManager.clear();
        List<InventoryAlertDTO> alerts = new ArrayList<>();
        List<Ingredient> ingredients = ingredientRepository.findAll();

        for (Ingredient ing : ingredients) {
            // 1. Get the latest stock level safely
            String sql = "SELECT stock_level FROM inventory_logs " +
                    "WHERE location_id = :locId AND ing_id = :ingId " +
                    "ORDER BY log_date DESC, log_id DESC LIMIT 1";

            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("locId", locationId);
            q.setParameter("ingId", ing.getId());

            Integer currentStock = 0; // Default to 0
            try {
                Object result = q.getSingleResult();
                // Check if result is null before casting
                if (result != null) {
                    // Use Number cast to be safe against BigInteger/Integer differences
                    currentStock = ((Number) result).intValue();
                }
            } catch (Exception e) {
                // NoResultException means this ingredient has never been logged at this location
                currentStock = 0;
            }

            // 2. Calculate Burn Rate (Simulation Logic)
            double dailyBurnRate = 40.0;

            // 3. Calculate Days Until Stockout (Prevent divide by zero)
            int daysUntilStockout = 0;
            if (dailyBurnRate > 0) {
                daysUntilStockout = (int) (currentStock / dailyBurnRate);
            }

            // 4. Determine Status
            String status = "OK";
            String recommendation = "None";

            // Since it is a primitive 'int', it will default to 0 if empty, but never null.
            int leadTime = ing.getLeadTimeDays();

            if (daysUntilStockout <= leadTime) {
                status = "CRITICAL";
                recommendation = "ORDER IMMEDIATELY (Lead time is " + leadTime + " days)";
            } else if (daysUntilStockout <= leadTime + 2) {
                status = "WARNING";
                recommendation = "Prepare Reorder";
            }

            // 5. Build DTO
            InventoryAlertDTO alert = InventoryAlertDTO.builder()
                    .ingredientName(ing.getName())
                    .currentStock(currentStock)
                    .dailyBurnRate(dailyBurnRate)
                    .daysUntilStockout(daysUntilStockout)
                    .status(status)
                    .recommendation(recommendation)
                    .build();

            alerts.add(alert);
        }
        return alerts;
    }
}
