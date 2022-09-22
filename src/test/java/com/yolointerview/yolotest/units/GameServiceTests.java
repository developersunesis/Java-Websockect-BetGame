package com.yolointerview.yolotest.units;

import com.yolointerview.yolotest.PlaceBetDto;
import com.yolointerview.yolotest.entities.Game;
import com.yolointerview.yolotest.entities.Player;
import com.yolointerview.yolotest.enums.StakeStatus;
import com.yolointerview.yolotest.exceptions.DuplicateGameIdException;
import com.yolointerview.yolotest.exceptions.GameDoesNotExistException;
import com.yolointerview.yolotest.exceptions.GameTimedOutException;
import com.yolointerview.yolotest.service.GameService;
import com.yolointerview.yolotest.service.GameServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class GameServiceTests {

    private GameService gameService;

    @BeforeEach
    public void initGameService() {
        gameService = spy(new GameServiceImpl());
    }

    private PlaceBetDto placeBetDto(String gameId) {
        return PlaceBetDto.builder().gameId(gameId).nickname("emmanuel")
                .number(5).stake(BigDecimal.TEN).build();
    }

    private String startRandomGame() throws DuplicateGameIdException {
        String gameId = UUID.randomUUID().toString();
        gameService.startNewGame(new Game(gameId));
        return gameId;
    }

    @Test
    @DisplayName("Throws error when the game session requested doesn't exist")
    public void requestedGameIdSessionDoesNotExist() {
        String invalidGameId = UUID.randomUUID().toString();
        PlaceBetDto placeBetDto = placeBetDto(invalidGameId);
        assertThrows(GameDoesNotExistException.class, () -> gameService.placeBet(placeBetDto));
    }

    @Test
    @DisplayName("Add or start a new game session")
    public void addNewGameAndCheckItExists() throws DuplicateGameIdException {
        // start the new game
        String gameId = UUID.randomUUID().toString();
        Game runningGame = gameService.startNewGame(new Game(gameId));

        assertNotNull(runningGame);
        assertTrue(gameService.isGameAvailable(gameId));
        assertNotNull(runningGame.getPlayers());
        assertTrue(runningGame.getPlayers().isEmpty());
        assertNull(runningGame.getCorrectNumber());
        assertTrue(runningGame.isActive());
    }

    @Test
    @DisplayName("Throws error when there's an attempt to place a bet on an ended game session")
    public void requestedGameIdSessionHasEnded() throws DuplicateGameIdException {
        // prepare mock instance for expired game
        String gameId = UUID.randomUUID().toString();
        Game game = spy(new Game(gameId));
        when(game.getTimeout()).thenReturn(LocalDateTime.now().minusSeconds(5));

        // start a new game
        gameService.startNewGame(game);

        PlaceBetDto placeBetDto = placeBetDto(gameId);
        assertThrows(GameTimedOutException.class, () -> gameService.placeBet(placeBetDto));
    }

    @Test
    @DisplayName("Player successfully places a bet in an active game session")
    public void playerSuccessfullyPlacesABet() throws DuplicateGameIdException, GameDoesNotExistException {
        // start the new game
        String gameId = startRandomGame();

        PlaceBetDto placeBetDto = placeBetDto(gameId);
        gameService.placeBet(placeBetDto);

        Game game = gameService.getGameById(gameId);
        HashMap<String, Player> playerHashMap = game.getPlayers();
        assertFalse(playerHashMap.isEmpty());

        Optional<Player> optionalPlayer = playerHashMap.values().stream().findFirst();
        assertNotNull(optionalPlayer.get());

        Player player = optionalPlayer.get();
        assertEquals(player.getNickname(), "emmanuel");
        assertEquals(player.getStakeAmount(), BigDecimal.TEN);
        assertNull(player.getStakeStatus());
    }

    @Test
    @DisplayName("When game is successfully ended, set the correct guess number for the game")
    public void endGameSessionAndSetCorrectGameNumber() throws GameDoesNotExistException,
            GameTimedOutException, DuplicateGameIdException {
        // start the new game
        String gameId = startRandomGame();

        Game endedGame = gameService.endGame(gameId);
        assertNotNull(endedGame);
        assertFalse(endedGame.isActive());

        Integer correctNumber = endedGame.getCorrectNumber();
        assertNotNull(correctNumber);
    }

    @Test
    @DisplayName("When game is successfully ended, players bet should be updated and rewarded accordingly")
    public void endGameSessionAndUpdatePlayersBet() throws GameDoesNotExistException,
            GameTimedOutException, DuplicateGameIdException {
        // start the new game
        String gameId = startRandomGame();

        // create mock bets
        PlaceBetDto placeBetDto = spy(placeBetDto(gameId));
        PlaceBetDto placeBetDto2 = spy(placeBetDto(gameId));
        PlaceBetDto placeBetDto3 = spy(placeBetDto(gameId));

        // place all mock bets
        gameService.placeBet(placeBetDto);
        gameService.placeBet(placeBetDto2);
        gameService.placeBet(placeBetDto3);

        int correctGuessedNumber = 4;
        BigDecimal player2Stake = placeBetDto2.getStake();
        when(placeBetDto2.getNumber()).thenReturn(correctGuessedNumber);

        Game mockedGame = spy(gameService.getGameById(gameId));
        when(mockedGame.getCorrectNumber()).thenReturn(correctGuessedNumber);
        when(gameService.getGameById(gameId)).thenReturn(mockedGame);

        Game endedGame = gameService.endGame(gameId);
        assertFalse(endedGame.isActive());
        assertNotNull(endedGame.getCorrectNumber());
        assertEquals(correctGuessedNumber, endedGame.getCorrectNumber());

        HashMap<String, Player> playerHashMap = endedGame.getPlayers();
        assertFalse(playerHashMap.isEmpty());
        assertEquals(3, playerHashMap.size());

        // obtain number of losing players after ended game
        long numberOfLosingPlayer = playerHashMap.values().stream()
                .filter(player -> player.getStakeStatus() != null && player.getStakeStatus().equals(StakeStatus.LOSS))
                .count();
        assertEquals(2, numberOfLosingPlayer);

        // obtain number of winning players
        List<Player> winingPlayers = playerHashMap.values().stream()
                .filter(player -> player.getStakeStatus() != null && player.getStakeStatus().equals(StakeStatus.WIN))
                .toList();
        long numberOfWiningPlayer = winingPlayers.size();
        assertEquals(1, numberOfWiningPlayer);

        // end of game balance is increased winning players (player2)
        BigDecimal expectedEndBalance = player2Stake.multiply(BigDecimal.valueOf(9.9));
        Optional<Player> optionalPlayer2 = winingPlayers.stream().findFirst();
        assertTrue(optionalPlayer2.isPresent());
        BigDecimal player2EndBalance = winingPlayers.stream().findFirst().get().getEndOfGameBalance();
        assertEquals(expectedEndBalance, player2EndBalance);
    }
}
