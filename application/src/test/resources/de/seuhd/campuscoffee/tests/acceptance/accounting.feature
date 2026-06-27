Feature: Coffee money and balances
  An admin sets the price; a user's balance reflects the coffees they drink, the beans they buy, and the
  deposits they pay. All money is in euro cents; a negative balance means the user owes the fund.

  Scenario: A coffee is valued at the price in effect when consumed
    Given the coffee user "maxmustermann"
    And an admin sets the price to 50 cents
    When the user drinks a coffee
    And an admin sets the price to 70 cents
    And the user drinks a coffee
    Then the user's balance is -120 cents

  Scenario: A user purchase credits the balance
    Given the coffee user "maxmustermann"
    When the user buys beans for 900 cents
    Then the user's balance is 900 cents

  Scenario: A deposit credits the balance
    Given the coffee user "maxmustermann"
    And an admin sets the price to 50 cents
    When the user drinks a coffee
    And an admin records a 1000 cent deposit for the user
    Then the user's balance is 950 cents

  Scenario: The admin global activity feed and CSV cover the whole installation
    Given the coffee user "maxmustermann"
    And an admin sets the price to 50 cents
    When the user drinks a coffee
    And an admin records a 1000 cent deposit for the user
    Then the global activity feed shows a DEPOSIT entry for the user
    And the activity CSV downloads with a UTF-8 BOM listing the user
