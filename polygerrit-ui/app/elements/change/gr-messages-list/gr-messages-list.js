// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
(function() {
  'use strict';

  Polymer({
    is: 'gr-messages-list',

    properties: {
      changeNum: Number,
      messages: {
        type: Array,
        value: function() { return []; },
      },
      reviewerUpdates: {
        type: Array,
        value: function() { return []; },
      },
      comments: Object,
      projectConfig: Object,
      showReplyButtons: {
        type: Boolean,
        value: false,
      },

      _expanded: {
        type: Boolean,
        value: false,
      },
    },

    scrollToMessage: function(messageID) {
      var el = this.$$('[data-message-id="' + messageID + '"]');
      if (!el) { return; }

      el.expanded = true;
      var top = el.offsetTop;
      for (var offsetParent = el.offsetParent;
           offsetParent;
           offsetParent = offsetParent.offsetParent) {
        top += offsetParent.offsetTop;
      }
      window.scrollTo(0, top);
      this._highlightEl(el);
    },

    _computeItems: function(messages, reviewerUpdates) {
      messages = messages || [];
      reviewerUpdates = reviewerUpdates || [];
      var mi = 0;
      var ri = 0;
      var result = [];
      var mDate;
      var rDate;
      for (var i = 0; i < messages.length; i++) {
        messages[i]._index = i;
      }
      while (mi < messages.length || ri < reviewerUpdates.length) {
        if (mi >= messages.length) {
          result = result.concat(reviewerUpdates.slice(ri));
          break;
        }
        if (ri >= reviewerUpdates.length) {
          result = result.concat(messages.slice(mi));
          break;
        }
        mDate = mDate || util.parseDate(messages[mi].date);
        rDate = rDate || util.parseDate(reviewerUpdates[ri].updated);
        if (rDate < mDate) {
          result.push(reviewerUpdates[ri++]);
          rDate = null;
        } else {
          result.push(messages[mi++]);
          mDate = null;
        }
      }
      return result;
    },

    _highlightEl: function(el) {
      var highlightedEls =
          Polymer.dom(this.root).querySelectorAll('.highlighted');
      for (var i = 0; i < highlightedEls.length; i++) {
        highlightedEls[i].classList.remove('highlighted');
      }
      function handleAnimationEnd() {
        el.removeEventListener('animationend', handleAnimationEnd);
        el.classList.remove('highlighted');
      }
      el.addEventListener('animationend', handleAnimationEnd);
      el.classList.add('highlighted');
    },

    _handleExpandCollapseTap: function(e) {
      e.preventDefault();
      this._expanded = !this._expanded;
      var messageEls = Polymer.dom(this.root).querySelectorAll('gr-message');
      for (var i = 0; i < messageEls.length; i++) {
        messageEls[i].expanded = this._expanded;
      }
    },

    _handleScrollTo: function(e) {
      this.scrollToMessage(e.detail.message.id);
    },

    _computeExpandCollapseMessage: function(expanded) {
      return expanded ? 'Collapse all' : 'Expand all';
    },

    /**
     * Computes message author's file comments for change's message.
     * Method uses this.messages to find next message and relies on messages
     * to be sorted by date field descending.
     * @param {!Object} comments Hash of arrays of comments, filename as key.
     * @param {!Object} message
     * @return {!Object} Hash of arrays of comments, filename as key.
     */
    _computeCommentsForMessage: function(comments, message) {
      if (message._index === undefined || !comments || !this.messages) {
        return [];
      }
      var messages = this.messages || [];
      var index = message._index;
      var authorId = message.author._account_id;
      var mDate = util.parseDate(message.date).getTime();
      // NB: Messages array has oldest messages first.
      var nextMDate;
      if (index > 0) {
        for (var i = index - 1; i >= 0; i--) {
          if (messages[i].author._account_id === authorId) {
            nextMDate = util.parseDate(messages[i].date).getTime();
            break;
          }
        }
      }
      var msgComments = {};
      for (var file in comments) {
        var fileComments = comments[file];
        for (var i = 0; i < fileComments.length; i++) {
          if (fileComments[i].author._account_id !== authorId) {
            continue;
          }
          var cDate = util.parseDate(fileComments[i].updated).getTime();
          if (cDate <= mDate) {
            if (nextMDate && cDate <= nextMDate) {
              continue;
            }
            msgComments[file] = msgComments[file] || [];
            msgComments[file].push(fileComments[i]);
          }
        }
      }
      return msgComments;
    },
  });
})();
