package com.bmp.sales.api;

import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.shared.id.RegisterId;
import java.util.Optional;

/** What other modules may ask sales about the current tenant. */
public interface SalesQueries {

    Optional<RegisterInfo> findRegister(RegisterId register);
}
