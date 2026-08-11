package io.tornado.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CoinRepository extends JpaRepository<Coin,Long>{List<Coin> findAllByActiveTrueOrderBySymbol(); Optional<Coin> findBySymbolIgnoreCase(String symbol);}
