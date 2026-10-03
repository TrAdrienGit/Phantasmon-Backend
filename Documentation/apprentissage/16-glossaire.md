# 16. Glossaire

| Terme | Définition | Chapitre |
|---|---|---|
| **Access token** | JWT de courte durée (20 min) joint à chaque requête | 8 |
| **Annotation** | Étiquette `@X` lue par un outil (compilateur, Spring, Hibernate, Lombok) | 2 |
| **Atomique** | Qui se produit entièrement ou pas du tout | 7, 11 |
| **Authentification** | Prouver qui l'on est (401 si échec) | 8 |
| **Autorisation** | Vérifier qu'on a le droit (403 si refus) | 8 |
| **Backend** | Programme serveur, source de vérité, sans interface | 1 |
| **Bean** | Objet créé et géré par le conteneur Spring | 5 |
| **Broadcast / diffusion** | Envoyer un même message à plusieurs destinataires | 10 |
| **Clé étrangère (FK)** | Colonne qui doit référencer une ligne existante d'une autre table | 7 |
| **Clé primaire (PK)** | Colonne qui identifie une ligne de façon unique | 7 |
| **Client hôte** | Le jeu du joueur qui exécute le moteur de combat | 12 |
| **Code de statut** | Nombre qui résume le résultat d'une requête HTTP (200, 404…) | 3 |
| **Commit / rollback** | Valider / annuler une transaction | 7 |
| **Conteneur (Docker)** | Programme isolé lancé depuis une image | 13 |
| **Conteneur (Spring)** | Le contexte d'application qui crée et relie les beans | 5 |
| **Contrôleur** | Classe qui reçoit les requêtes HTTP et délègue au service | 6 |
| **DTO** | Objet qui décrit la forme exacte des données échangées (ici des records) | 1, 6 |
| **Dépôt (repository)** | Interface d'accès à une table ; implémentation générée par Spring Data | 7 |
| **Empreinte de serveur** | Hash de l'adresse du serveur Minecraft, sert à regrouper les joueurs | 10 |
| **Entité** | Classe Java correspondant à une table (JPA) | 7 |
| **Enveloppe** | Format commun des messages WebSocket : `{type, data}` | 10 |
| **Filtre** | Composant qui voit passer chaque requête HTTP | 6 |
| **Flush** | Envoi immédiat des écritures en attente vers la base, sans valider la transaction | 7 |
| **Flyway** | Outil qui applique les scripts de migration du schéma | 7 |
| **Gradle** | Outil de build (dépendances, compilation, tests, jar) | 4 |
| **Handshake** | Requête HTTP initiale qui ouvre une connexion WebSocket | 10 |
| **Heartbeat** | Message périodique qui prouve qu'un client est toujours là | 10 |
| **Hibernate** | Implémentation de JPA | 7 |
| **Idempotence** | Propriété d'une opération qu'on peut répéter sans effet supplémentaire | 3, 9 |
| **Index unique** | Contrainte : deux lignes ne peuvent pas avoir la même valeur | 7 |
| **Injection de dépendances** | Recevoir ses collaborateurs dans le constructeur au lieu de les créer | 5 |
| **Jackson** | Bibliothèque de conversion JSON côté backend | 3 |
| **Jar** | Archive exécutable d'un programme Java | 4 |
| **JPA** | Norme Java de correspondance objets ↔ tables | 7 |
| **JSON** | Format texte des données échangées | 3 |
| **JSONB** | Type PostgreSQL qui stocke un document JSON | 7 |
| **JWT** | Jeton signé contenant l'identité, vérifiable sans stockage | 8 |
| **Lombok** | Outil qui génère getters, setters, constructeurs, logger | 2 |
| **Migration** | Script SQL numéroté qui fait évoluer le schéma | 7 |
| **Mojang `hasJoined`** | Service qui confirme qu'un compte a rejoint une session donnée | 8 |
| **`Optional`** | Conteneur « peut-être une valeur », à la place de `null` | 2 |
| **Présence** | État en mémoire d'un joueur connecté (groupe, position, Ghost) | 10 |
| **Record** | Classe immuable de données à syntaxe courte | 2 |
| **Refresh token** | JWT de longue durée (7 jours) servant à obtenir de nouveaux jetons | 8 |
| **Relais** | Le serveur transmet un message sans l'interpréter | 12 |
| **REST** | Convention d'API : ressources + méthodes HTTP | 3 |
| **Service** | Classe qui porte les règles métier | 1, 9 |
| **Singleton** | Une seule instance partagée (cas par défaut des beans) | 5 |
| **SLF4J / Logback** | Interface / implémentation de journalisation | 13 |
| **snake_case** | Convention `owner_uuid` (JSON du projet) ; Java utilise le camelCase `ownerUuid` | 3 |
| **Source de vérité** | Le composant dont la version des données fait foi | 1 |
| **Spring Boot** | Framework qui configure automatiquement une application Spring | 5 |
| **Spring Security** | Module qui gère authentification et autorisation | 6, 8 |
| **`synchronized`** | Un seul thread à la fois dans le bloc ou la méthode | 2, 11 |
| **TDD** | Écrire le test avant le code | 14 |
| **Testcontainers** | Démarre de vraies dépendances (PostgreSQL) dans Docker pendant les tests | 14 |
| **Thread** | Fil d'exécution ; le serveur en utilise plusieurs en parallèle | 2 |
| **Transaction** | Groupe d'écritures en tout ou rien | 7 |
| **TTL** | Durée de vie au-delà de laquelle une donnée est considérée expirée | 10 |
| **UUID** | Identifiant unique sur 128 bits | 2 |
| **Validation (Bean)** | Annotations `@NotNull`, `@Min`… vérifiées sur les DTO | 6 |
| **WebSocket** | Connexion réseau bidirectionnelle qui reste ouverte | 10 |
