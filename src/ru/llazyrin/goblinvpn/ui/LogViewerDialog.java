package ru.llazyrin.goblinvpn.ui;

import android.content.Context;
import android.widget.Toast;

import ru.llazyrin.goblinvpn.model.VpnServer;
import ru.llazyrin.goblinvpn.service.VpnState;

/**
 * @deprecated Локальный просмотр логов полностью отключен в приложении.
 * Логирование ведётся удаленно через защищённый канал AppTelemetryClient на сервер.
 */
@Deprecated
public class LogViewerDialog {

    private final Context context;

    public LogViewerDialog(Context context, VpnServer currentServer, VpnState currentState) {
        this.context = context;
    }

    public void show() {
        if (context != null) {
            Toast.makeText(context, "Локальный журнал логов отключен разработчиком", Toast.LENGTH_SHORT).show();
        }
    }
}
