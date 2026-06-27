package org.alexmond.jvmlens.it;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository — drives JDBC {@code execute*} (the {@code db} capture). */
public interface WidgetRepository extends JpaRepository<Widget, Long> {

}
