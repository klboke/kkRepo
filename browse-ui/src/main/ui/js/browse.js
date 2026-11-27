import { bindShellEvents } from "./shell/navigation-events.js";
import { bindSearchEvents } from "./search/events.js";
import { bindRepositoriesEvents } from "./repositories/events.js";
import { bindUploadEvents } from "./upload/events.js";
import { bindAccountEvents } from "./account/events.js";
import { initializeLoginReturnTo } from "./shell/session.js";
import { bootstrap } from "./shell/start.js";

bindShellEvents();
bindSearchEvents();
bindRepositoriesEvents();
bindUploadEvents();
bindAccountEvents();
initializeLoginReturnTo();
bootstrap();
