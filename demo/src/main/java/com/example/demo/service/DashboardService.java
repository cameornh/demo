package com.example.demo.service;

import com.example.demo.dto.InventoryAlertDTO;
import com.example.demo.model.Ingredient;
import com.example.demo.model.Event;
import com.example.demo.repository.IngredientRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

        //get upcoming events for next 7 days, calculate forecasted demand
        String eventSql = "SELECT * FROM events WHERE event_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 7";
        Query eventQuery = entityManager.createNativeQuery(eventSql, Event.class);
        List<Event> upcomingEvents = eventQuery.getResultList();

        for (Ingredient ing : ingredients) {
            //get the latest stock level
            //Integer currentStock = 0; // Default to 0
            Integer currentStock = getCurrentStock(locationId, ing.getId());
            /**
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
            **/

            //identify highest impact event
            double maxMultiplierFound = 1.0;
            String highestImpactEventName = "";
            for (Event e : upcomingEvents) {
                if (e.getImpactMultiplier() > maxMultiplierFound) {
                    maxMultiplierFound = e.getImpactMultiplier();
                    highestImpactEventName = e.getEventName();
                }
            }

            //calculate burn rate
            double baseBurnRate = 40.0; //normal daily usage
            double surgeBurnRate = baseBurnRate * maxMultiplierFound;
            int daysUntilStockout = (surgeBurnRate > 0) ? (int) (currentStock / surgeBurnRate) : 999;
            //double totalProjectedUsageNext7Days = 0;
            //String eventAlertNote = "";

            //dynamic pricing
            double suggestedMarkup = 0.0;
            String pricingRationale = "Standard Pricing";

            if (maxMultiplierFound > 1.2) {
                if (daysUntilStockout <= ing.getLeadTimeDays()) {
                    //high demand, low supply (aggressive surge)
                    suggestedMarkup = 0.20;
                    pricingRationale = "Critical Stockout Risk + " + highestImpactEventName;
                } else {
                    //high demand, high stock (moderate surge)
                    suggestedMarkup = 0.10;
                    pricingRationale = "Increased Demand: " + highestImpactEventName;
                }
            } else if (daysUntilStockout <= 1) {
                //no event, low stock (scarcity pricing)
                suggestedMarkup = 0.05;
                pricingRationale = "Inventory Scarcity";
            }
            /**
            //tracking variables
            double maxMultiplierFound = 1.0;
            String highestImpactEventName = "";

            for (int day = 0; day < 7; day++) {
                LocalDate futureDate = LocalDate.now().plusDays(day);
                double dailyMultiplier = 1.0;

                //check if there's an event on that date
                for (Event e: upcomingEvents) {
                    if (e.getEventDate().equals(futureDate)) {
                        dailyMultiplier = e.getImpactMultiplier();

                        //only capture name if this event is most impactful
                        if (dailyMultiplier > maxMultiplierFound) {
                            maxMultiplierFound = dailyMultiplier;
                            highestImpactEventName = e.getEventName();
                        }
                    }
                }
                totalProjectedUsageNext7Days += (baseBurnRate * dailyMultiplier);
            }

            double avgEventAdjustedBurnRate = totalProjectedUsageNext7Days / 7.0;

            String eventAlertNote = highestImpactEventName.isEmpty() ?
                    "" : " [High Impact: " + highestImpactEventName + "]";

            //calculate days until stockout
            int daysUntilStockout = 999; //if burn rate is 0, stock will last "forever"
            if (avgEventAdjustedBurnRate > 0) {
                daysUntilStockout = (int) (currentStock / avgEventAdjustedBurnRate);
            }

            //determine status
            int leadTime = ing.getLeadTimeDays();
            String status = "OK";
            String recommendation = "No action needed";

            if (daysUntilStockout <= leadTime) {
                status = "CRITICAL";
                recommendation = "ORDER IMMEDIATELY: Stockout predicted in " + daysUntilStockout + " days." + eventAlertNote;
            } else if (daysUntilStockout <= leadTime + 2) {
                status = "WARNING";
                recommendation = "Low stock. Reorder within 48 hours." + eventAlertNote;
            }
            **/

            //determine alert status
            String status = daysUntilStockout <= ing.getLeadTimeDays() ? "CRITICAL" :
                    (daysUntilStockout <= ing.getLeadTimeDays() + 2 ? "WARNING" : "OK");

            //build DTO
            alerts.add(InventoryAlertDTO.builder()
                    .ingredientName(ing.getName())
                    .currentStock(currentStock)
                    .dailyBurnRate(Math.round(surgeBurnRate * 10.0) / 10.0) //round to 1 decimal
                    .daysUntilStockout(daysUntilStockout)
                    .status(status)
                    .suggestedPriceMarkup(suggestedMarkup)
                    .pricingRationale(pricingRationale)
                    .recommendation(status.equals("CRITICAL") ? "ORDER IMMEDIATELY" : "Monitor Stock")
                    .build());
        }
        return alerts;
    }

    private Integer getCurrentStock(int locationId, long ingId) {
        String sql = "SELECT stock_level FROM inventory_logs " +
                "WHERE location_id = :locId AND ing_id = :ingId " +
                "ORDER BY log_date DESC, log_id DESC LIMIT 1";

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("locId", locationId);
        q.setParameter("ingId", ingId);

        try {
            Object result = q.getSingleResult();
            return result != null ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            return 0; //no logs found (e.g. new store)
        }
    }
}
