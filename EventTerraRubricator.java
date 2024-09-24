package ext.terralink.lms.core.person.career.rubricator;

import mira.event.EventMessage;
import mira.vv.rubricator.standard.StandardRubricator;

/**
 * Справочник "Событие"
 *
 * @author Denis Lapin
 * @since 30.05.2024
 */
public class EventTerraRubricator extends StandardRubricator {

    public static final String NAME = "eventrubr";

    public EventTerraRubricator() {
        super(NAME, EventMessage.event);
    }
}
