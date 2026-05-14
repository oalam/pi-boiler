#pragma once
#include <cstdint>
#include "boiler/boiler_controller.hpp"

class Esp32Reporter;
class Esp32Actuators;

// Interface web servie par l'ESP32 (accès depuis un téléphone).
//
// Modèle de concurrence : le serveur web tourne dans la tâche AsyncTCP, le cœur
// de régulation dans loop(). On ne touche donc JAMAIS le contrôleur depuis les
// handlers web :
//   - les commandes sont déposées dans une file et drainées par loop() ;
//   - l'état est publié par loop() dans un instantané que les handlers lisent.

// Requête issue de l'UI : soit une commande, soit une mise à jour des réglages.
struct WebRequest {
    bool                   is_settings;
    boiler::Command        command;
    boiler::BoilerSettings settings;
};

// À appeler dans setup() après la création du contrôleur.
void webui_begin(boiler::BoilerController* controller,
                 Esp32Reporter* reporter,
                 Esp32Actuators* actuators);

// À appeler à chaque tour de loop() : met à jour l'instantané lu par l'UI.
void webui_publish();

// Drainé par loop() : récupère la prochaine requête en attente (false si vide).
bool webui_next_request(WebRequest& out);
