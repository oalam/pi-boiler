#pragma once
#include <cstdint>

// Cœur de régulation de la chaudière à granulés — types de base.
//
// Aucune dépendance matérielle : ce header est compilable sur PC, ESP32 ou Pi.
// Toutes les durées sont en millisecondes ; les températures en degrés Celsius.

namespace boiler {

// États de la machine à états de régulation.
enum class State {
    Stopped,      // à l'arrêt — aucune combustion
    Igniting,     // démarrage : cycles de combustion lancés, montée en température non confirmée
    Running,      // fonctionnement normal en cycles de combustion
    Slowing,      // mode ralenti : consigne atteinte, relances périodiques pour entretenir le feu
    Overheating,  // surchauffe : combustion coupée, circulation forcée
    Locked,       // verrouillage de sécurité — nécessite un réarmement manuel (commande Start)
};

// Organes pilotés. L'ordre fixe les indices internes — voir kEngineCount.
enum class Engine {
    Tremie,       // vis sans fin de la trémie
    Bruleur,      // vis sans fin du brûleur
    Ventilo,      // ventilateur (air de combustion)
    Circulateur,  // circulateur / vanne 4 voies
    Laddomat,     // pompe de charge
};

// Commandes externes (API REST, bouton physique, superviseur...).
enum class Command {
    Start,        // démarrage, ou réarmement depuis l'état Locked
    Stop,         // arrêt des cycles de combustion
    Shutdown,     // arrêt complet et mise à l'état sûr
    LoadPellets,  // amorçage manuel du brûleur (uniquement à l'arrêt)
};

// Alertes remontées au superviseur.
enum class Alert {
    Overheat,                // température au-delà du seuil de surchauffe logiciel
    LockoutIgnitionFailed,   // allumage raté : pas de montée en température dans le délai imparti
    LockoutLowTemp,          // verrouillage : température basse prolongée en fonctionnement
    SensorFailure,           // sonde de température muette
    EngineFault,             // défaut sur une vis (protection thermique / bourrage)
};

// Réglages modifiables par l'utilisateur. Les valeurs sont bornées par le
// contrôleur (voir params:: ci-dessous et la notice MONEX, tableau 4).
struct BoilerSettings {
    float    consigne_celsius = 75.0f;                  // consigne eau de chauffage
    uint32_t delay_off_ms     = 20u * 1000u;            // arrêt des vis entre deux cycles de combustion
    uint32_t rest_duration_ms = 60u * 60u * 1000u;      // repos en mode ralenti entre deux relances
};

// Paramètres fixes de régulation, alignés sur la notice du régulateur MONEX d'origine.
namespace params {

// --- Cycle de combustion ---
constexpr uint32_t HEATING_RUN_DURATION_MS = 3u * 1000u;   // marche de la vis trémie
constexpr uint32_t BURNER_POST_DURATION_MS = 5u * 1000u;   // post-fonctionnement de la vis brûleur (vide le flexible)

// --- Mode ralenti ---
constexpr uint32_t SLOWDOWN_ENGINES_DURATION_MS = 2u * 60u * 1000u;  // relance des vis : 2 min (notice MONEX)
constexpr uint32_t SLOWDOWN_FAN_DURATION_MS =
    SLOWDOWN_ENGINES_DURATION_MS + BURNER_POST_DURATION_MS;          // ventilateur aligné sur les vis (évite l'étouffement)

// --- Amorçage manuel ---
constexpr uint32_t PELLETS_LOADING_DURATION_MS = 50u * 1000u;

// --- Sécurités ---
constexpr uint32_t IGNITION_TIMEOUT_MS       = 60u * 60u * 1000u;  // allumage raté au-delà de 60 min
constexpr float    IGNITION_MIN_CELSIUS      = 30.0f;              // température prouvant l'allumage
constexpr uint32_t LOW_TEMP_LOCKOUT_DELAY_MS = 30u * 60u * 1000u;  // tolérance avant verrouillage température basse
constexpr float    LOW_TEMP_LOCKOUT_CELSIUS  = 30.0f;
constexpr float    OVERHEAT_CELSIUS          = 90.0f;              // seuil de surchauffe LOGICIEL
constexpr float    OVERHEAT_CLEAR_CELSIUS    = 85.0f;              // hystérésis de sortie de surchauffe
// NB : le thermostat physique 95 °C reste une coupure matérielle indépendante de ce logiciel.

// --- Circulation hydraulique (fonction de la température) ---
constexpr float CIRCULATEUR_ON_CELSIUS = 40.0f;  // anti-condensation
constexpr float LADDOMAT_ON_CELSIUS    = 60.0f;

// --- Surveillance de la sonde ---
constexpr uint32_t SENSOR_FAILURE_ALERT_THRESHOLD = 5;  // lectures muettes consécutives avant alerte

// --- Bornes de réglage (notice MONEX, tableau 4) ---
constexpr float    CONSIGNE_MIN_CELSIUS = 60.0f;
constexpr float    CONSIGNE_MAX_CELSIUS = 90.0f;
constexpr uint32_t DELAY_OFF_MIN_MS     = 3u * 1000u;
constexpr uint32_t DELAY_OFF_MAX_MS     = 90u * 1000u;
constexpr uint32_t REST_DURATION_MIN_MS = 3u * 60u * 1000u;
constexpr uint32_t REST_DURATION_MAX_MS = 90u * 60u * 1000u;

}  // namespace params

}  // namespace boiler
