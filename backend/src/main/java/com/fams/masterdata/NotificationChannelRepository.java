package com.fams.masterdata;


import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the NotificationChannelMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class NotificationChannelRepository {

    private final NotificationChannelMapper mapper;

    NotificationChannelRepository(NotificationChannelMapper mapper) {
        this.mapper = mapper;
    }

    NotificationChannelState lockState() {
        return mapper.lockState();
    }

    NotificationChannelState state() {
        return mapper.state();
    }

    NotificationChannelRow find(long id) {
        return mapper.find(id);
    }

    String accountUsername(long id) {
        return mapper.accountUsername(id);
    }

    int supersede(long id) {
        return mapper.supersede(id);
    }

    int insert(NewNotificationChannelRow row) {
        return mapper.insert(row);
    }

    int activate(long expectedVersion, long version, long id) {
        return mapper.activate(expectedVersion, version, id);
    }
}
