package com.dypcet.vayora.supply;

import com.dypcet.vayora.supply.SupplyGapService.Gap;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/supply-gaps")
@PreAuthorize("hasRole('ADMIN')")
public class SupplyGapController {

    private final SupplyGapService supply;

    public SupplyGapController(SupplyGapService supply) {
        this.supply = supply;
    }

    /** Days ahead where passengers searched a stretch and found no ride, and who has been told. */
    @GetMapping
    public List<Gap> upcoming() {
        return supply.upcoming();
    }
}
