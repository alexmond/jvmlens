package org.alexmond.jvmlens.it;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A servlet-backed endpoint — routes through {@code HttpServlet} (the {@code web}
 * capture).
 */
@RestController
public class WidgetController {

	private final WidgetRepository repo;

	public WidgetController(WidgetRepository repo) {
		this.repo = repo;
	}

	@GetMapping("/widgets")
	public List<Widget> all() {
		return this.repo.findAll();
	}

}
