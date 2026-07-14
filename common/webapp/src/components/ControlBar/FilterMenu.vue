<template>
  <div class="filter-menu" ref="root">
    <div class="filter-button" :class="{active: open}" @click="open = !open">
      <svg viewBox="0 0 30 30">
        <path d="M5,6h20l-7.5,9v8l-5,2v-10L5,6z"/>
      </svg>
      <span class="label">{{ $t("filters.button") }}</span>
    </div>
    <div v-if="open" class="filter-panel">

      <template v-if="playerSet">
        <div class="group-title">{{ $t("players.title") }}</div>
        <div class="filter-row" @click="toggle(playerSet)">
          <span class="row-label">{{ $t("filters.showPlayers") }}</span>
          <div class="switch" :class="{on: playerSet.visible}"></div>
        </div>
        <div class="search">
          <input type="text" v-model="search" :placeholder="$t('filters.searchPlayer')"
                 @keydown.stop @keyup.stop>
        </div>
        <div class="player-list" v-if="search">
          <div v-if="!matchedPlayers.length" class="empty">{{ $t("filters.noPlayers") }}</div>
          <div v-for="player in matchedPlayers" :key="player.id" class="player-row" @click="gotoPlayer(player)">
            <img :src="player.playerHead" alt="" @error="steve">
            <span class="row-label">{{ player.name }}</span>
          </div>
        </div>
      </template>

      <div class="group-title">{{ $t("filters.ores") }}</div>
      <div v-for="set in oreSets" :key="set.id" class="filter-row" @click="toggle(set)">
        <span class="row-label">{{ set.label }}</span>
        <div class="switch" :class="{on: set.visible}"></div>
      </div>
    </div>
  </div>
</template>

<script>
const PLAYER_SET_ID = "bm-players";

export default {
  name: "FilterMenu",
  data() {
    return {
      open: false,
      search: "",
      markers: this.$bluemap.mapViewer.markers.data,
    }
  },
  computed: {
    oreSets() {
      return this.markers.markerSets
          .filter(set => set.toggleable && set.id.startsWith("ore-"))
          .sort((a, b) => (a.sorting || 0) - (b.sorting || 0));
    },
    playerSet() {
      return this.markers.markerSets.find(set => set.id === PLAYER_SET_ID);
    },
    matchedPlayers() {
      if (!this.playerSet) return [];
      const query = this.search.trim().toLowerCase();
      if (!query) return [];
      return this.playerSet.markers
          .filter(marker => marker.type === "player" && (marker.name || "").toLowerCase().includes(query))
          .sort((a, b) => (a.name || "").localeCompare(b.name || ""));
    }
  },
  mounted() {
    document.addEventListener("click", this.closeOnOutsideClick);
  },
  unmounted() {
    document.removeEventListener("click", this.closeOnOutsideClick);
  },
  methods: {
    toggle(set) {
      set.visible = !set.visible;
      set.saveState();
    },
    closeOnOutsideClick(event) {
      if (this.open && this.$refs.root && !this.$refs.root.contains(event.target)) this.open = false;
    },
    // Mirrors Menu/MarkerItem.click(follow=true): a player on another map needs the map switched
    // first, and following only makes sense while the player-markers are actually rendered.
    async gotoPlayer(marker) {
      const cm = this.$bluemap.mapViewer.controlsManager;
      if (cm.controls && cm.controls.stopFollowingPlayerMarker) cm.controls.stopFollowingPlayerMarker();

      if (marker.foreign) {
        const matchingMap = await this.$bluemap.findPlayerMap(marker.playerUuid);
        if (!matchingMap) return;
        await this.$bluemap.switchMap(matchingMap.data.id);
      }

      if (cm.controls && cm.controls.followPlayerMarker && marker.visible) {
        cm.controls.followPlayerMarker(marker);
      }

      cm.position.copy(marker.position);
      this.open = false;
    },
    steve(event) {
      event.target.src = "assets/steve.png";
    }
  }
}
</script>

<style lang="scss">
@import "/src/scss/variables.scss";

.filter-menu {
  position: relative;
  pointer-events: auto;

  .filter-button {
    display: flex;
    align-items: center;
    gap: 0.4em;

    cursor: pointer;
    user-select: none;

    height: 2em;
    padding: 0 0.75em 0 0.5em;

    background-color: var(--theme-bg);
    color: var(--theme-fg);

    &:hover {
      background-color: var(--theme-bg-hover);
    }

    &.active {
      background-color: var(--theme-bg-light);
    }

    svg {
      height: 1.4em;
      width: 1.4em;
      flex-shrink: 0;
      fill: var(--theme-fg-light);
    }

    .label {
      line-height: 2em;
      white-space: nowrap;
    }
  }

  .filter-panel {
    position: absolute;
    top: calc(100% + 0.5em);
    left: 0;

    min-width: 16em;
    padding: 0.5em 0;

    background-color: var(--theme-bg);
    color: var(--theme-fg);

    .group-title {
      padding: 0.25em 0.75em 0.5em;
      font-size: 0.8em;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--theme-fg-light);

      &:not(:first-child) {
        margin-top: 0.5em;
        border-top: solid 1px var(--theme-bg-light);
        padding-top: 0.75em;
      }
    }

    .filter-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1em;

      cursor: pointer;
      user-select: none;

      padding: 0.5em 0.75em;

      &:hover {
        background-color: var(--theme-bg-hover);
      }

      .row-label {
        white-space: nowrap;
      }

      .switch {
        flex-shrink: 0;
        height: 1em;
        width: 2em;

        border-radius: 1em;
        background-color: var(--theme-bg-light);

        transition: background-color 0.3s;

        &::after {
          content: "";
          display: block;
          width: 0.75em;
          height: 0.75em;
          border-radius: 100%;

          background-color: var(--theme-bg);

          position: relative;
          top: 0.125em;
          left: 0.125em;

          transition: left 0.3s;
        }

        &.on {
          background-color: var(--theme-switch-button-on);

          &::after {
            left: 1.125em;
          }
        }
      }
    }

    .search {
      padding: 0.25em 0.75em 0.5em;

      input {
        width: 100%;
        box-sizing: border-box;

        padding: 0.4em 0.5em;

        border: solid 1px var(--theme-bg-light);
        background-color: var(--theme-bg-light);
        color: var(--theme-fg);

        outline: none;

        &:focus {
          border-color: var(--theme-switch-button-on);
        }
      }
    }

    .player-list {
      max-height: 14em;
      overflow-y: auto;

      .empty {
        padding: 0.25em 0.75em 0.5em;
        color: var(--theme-fg-light);
        font-size: 0.9em;
      }

      .player-row {
        display: flex;
        align-items: center;
        gap: 0.5em;

        cursor: pointer;
        user-select: none;

        padding: 0.35em 0.75em;

        &:hover {
          background-color: var(--theme-bg-hover);
        }

        img {
          width: 1.4em;
          height: 1.4em;
          flex-shrink: 0;
          image-rendering: pixelated;
        }

        .row-label {
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
      }
    }
  }
}
</style>
