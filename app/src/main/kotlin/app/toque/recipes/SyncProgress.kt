package app.toque.recipes

import android.os.Handler
import android.os.Looper

/**
 * Avancement de la synchronisation des recettes, gardé hors des écrans : celui des Réglages est
 * reconstruit à chaque ouverture, alors que la synchronisation continue en arrière-plan.
 * Lu et écouté sur le fil principal ; [report] s'appelle depuis n'importe quel fil.
 */
object SyncProgress {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    /** Dernier message d'avancement ; null quand aucune synchronisation n'est en cours. */
    var message: String? = null
        private set

    val running: Boolean get() = message != null

    /** Début demandé depuis l'interface (fil principal) : pris en compte tout de suite. */
    fun start(text: String) {
        message = text
        for (l in ArrayList(listeners)) l()
    }

    /** Avancement, ou fin avec null. */
    fun report(text: String?) {
        main.post {
            message = text
            for (l in ArrayList(listeners)) l()
        }
    }

    /** À appeler sur le fil principal. Rend la fonction de désabonnement. */
    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }
}
