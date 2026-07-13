package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.AuthTicketDao;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NexusAuthenticationTicketService {
  private final TicketStore ticketStore;
  private final Duration ttl;

  @Autowired
  public NexusAuthenticationTicketService(
      AuthTicketDao authTicketDao,
      @Value("${kkrepo.security.authentication-ticket-ttl-seconds:300}") long ttlSeconds) {
    this(new MysqlTicketStore(authTicketDao), ttlSeconds);
  }

  private NexusAuthenticationTicketService(TicketStore ticketStore, long ttlSeconds) {
    this.ticketStore = ticketStore;
    this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
  }

  static NexusAuthenticationTicketService inMemory(long ttlSeconds) {
    return new NexusAuthenticationTicketService(new InMemoryTicketStore(), ttlSeconds);
  }

  public String createTicket(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new SecurityValidationException("userId is required");
    }
    String token = UUID.randomUUID().toString();
    ticketStore.create(tokenHash(token), userId, Instant.now().plus(ttl));
    return token;
  }

  @Transactional
  public Optional<String> redeemTicket(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return ticketStore.consume(tokenHash(token), Instant.now());
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security.authentication-ticket-cleanup-interval-ms:60000}",
      initialDelayString = "${kkrepo.security.authentication-ticket-cleanup-initial-delay-ms:60000}")
  public void cleanupExpiredTickets() {
    ticketStore.deleteExpired(Instant.now());
  }

  private static String tokenHash(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token is required");
    }
    return SecurityHashing.sha256(token);
  }

  interface TicketStore {
    void create(String tokenHash, String userId, Instant expiresAt);

    Optional<String> consume(String tokenHash, Instant now);

    void deleteExpired(Instant now);
  }

  private static class MysqlTicketStore implements TicketStore {
    private final AuthTicketDao authTicketDao;

    private MysqlTicketStore(AuthTicketDao authTicketDao) {
      this.authTicketDao = authTicketDao;
    }

    @Override
    public void create(String tokenHash, String userId, Instant expiresAt) {
      authTicketDao.insert(tokenHash, userId, expiresAt);
    }

    @Override
    public Optional<String> consume(String tokenHash, Instant now) {
      return authTicketDao.consume(tokenHash, now);
    }

    @Override
    public void deleteExpired(Instant now) {
      authTicketDao.deleteExpired(now);
    }
  }

  private static class InMemoryTicketStore implements TicketStore {
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    @Override
    public void create(String tokenHash, String userId, Instant expiresAt) {
      tickets.put(tokenHash, new Ticket(userId, expiresAt));
    }

    @Override
    public Optional<String> consume(String tokenHash, Instant now) {
      Ticket ticket = tickets.remove(tokenHash);
      if (ticket == null || !ticket.expiresAt().isAfter(now)) {
        return Optional.empty();
      }
      return Optional.of(ticket.userId());
    }

    @Override
    public void deleteExpired(Instant now) {
      tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
  }

  private record Ticket(String userId, Instant expiresAt) {
  }
}
