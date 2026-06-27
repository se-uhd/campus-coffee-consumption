Feature: User coffee consumption
  A user bumps their own coffee count via their secret capability token.

  Scenario: A user adds a coffee
    Given the user "maxmustermann"
    When the user adds a coffee
    Then the request succeeds and the coffee count is 1

  Scenario: A user adds two coffees
    Given the user "maxmustermann"
    When the user adds a coffee
    And the user adds a coffee
    Then the request succeeds and the coffee count is 2

  Scenario: A user undoes a coffee within the grace period
    Given the user "maxmustermann"
    When the user adds a coffee
    And the user undoes a coffee
    Then the request succeeds and the coffee count is 0

  Scenario: A user cannot undo when there is nothing to undo
    Given the user "maxmustermann"
    When the user undoes a coffee
    Then the request is rejected with status 409
